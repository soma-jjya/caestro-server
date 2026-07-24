package com.caestro.server.domain.signaling.service;

import com.caestro.server.domain.devicespec.service.DeviceSpecService;
import com.caestro.server.domain.session.service.SessionService;
import com.caestro.server.domain.signaling.dto.request.SignalingRequest;
import com.caestro.server.domain.signaling.dto.response.SignalingResponse;
import com.caestro.server.domain.signaling.entity.SessionInfo;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalingService {

    private final RedisTemplate<String, String> redisTemplate;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final SessionService sessionService;
    private final DeviceSpecService deviceSpecService;
    private final LiteTokenService liteTokenService;
    private final SignalingDiagnosticLogger diagnosticLogger;
    private final SignalingRelaySender relaySender;

    /**
     * 디렉터(찍히는 사람)가 새로운 WebRTC 세션(방)을 생성합니다.
     * 고유한 8자리 세션 코드를 발급하고, Redis에 대기(WAITING) 상태로 저장한 뒤 클라이언트에게 코드를 반환합니다.
     *
     * @param socket 디렉터의 웹소켓 세션 객체
     * @param msg    클라이언트로부터 받은 세션 생성 요청 메시지
     */
    public void createSession(WebSocketSession socket, SignalingRequest msg) {
        // 1. 8자리 세션 코드 발급
        String sessionCode = UUID.randomUUID().toString().substring(0, 8);

        // 2. 라이트 모드(비로그인 촬영자) 참여 토큰 발급 (세션에 귀속)
        String liteToken = liteTokenService.issue(sessionCode);

        // 3. 세션 정보 생성 및 디렉터 정보 세팅 (핸드셰이크 시 인증된 userId 포함)
        Long directorUserId = getUserId(socket);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
        SessionInfo info = new SessionInfo();
        info.setSessionCode(sessionCode);
        info.setDirectorUserId(directorUserId);
        info.setDirectorSocketId(socket.getId());
        info.setStatus("WAITING");
        info.setExpiresAt(expiresAt);
        info.setLiteToken(liteToken);

        // 4. Redis에 세션 정보 및 소켓-세션 매핑 저장
        saveSessionInfo(sessionCode, info);
        redisTemplate.opsForValue().set("socket:" + socket.getId(), sessionCode, 10, TimeUnit.MINUTES);

        // 5. DB에 세션 영구 저장 (Redis는 실시간 상태, DB는 영구 기록 용도)
        sessionService.createSession(sessionCode, directorUserId, expiresAt, liteToken);

        // 6. 디렉터에게 세션 생성 완료 응답 전송 (라이트 모드 참여 토큰 포함)
        SignalingResponse response = SignalingResponse.builder()
                .type("SESSION_CREATED")
                .sessionCode(sessionCode)
                .liteToken(liteToken)
                .build();

        sessionManager.sendMessage(socket.getId(), response);
        log.info("Session created: {}", sessionCode);
    }

    /**
     * 카메라맨(찍어주는 사람)이 발급된 세션 코드를 통해 기존 방에 입장합니다.
     * 방이 존재하면 상태를 연결됨(CONNECTED)으로 변경하고, 디렉터에게 카메라맨이 입장했음을 알립니다.
     *
     * @param socket 카메라맨의 웹소켓 세션 객체
     * @param msg    입장할 세션 코드(sessionCode)가 담긴 요청 메시지
     */
    public void joinSession(WebSocketSession socket, SignalingRequest msg) {
        // 1. 세션 코드로 방 정보 조회
        String sessionCode = msg.sessionCode();
        SessionInfo info = getSessionInfo(sessionCode);

        // 2. 존재하지 않는 세션이면 에러 응답
        if (info == null) {
            SignalingResponse errorResponse = SignalingResponse.builder()
                    .type("ERROR")
                    .sessionCode(sessionCode)
                    .message(ErrorCode.SESSION_NOT_FOUND.getMessage())
                    .build();
            sessionManager.sendMessage(socket.getId(), errorResponse);
            return;
        }

        // 3. 이미 촬영자가 연결된 세션이면 에러 응답
        if ("CONNECTED".equals(info.getStatus())) {
            SignalingResponse errorResponse = SignalingResponse.builder()
                    .type("ERROR")
                    .sessionCode(sessionCode)
                    .message(ErrorCode.SESSION_ALREADY_CONNECTED.getMessage())
                    .build();
            sessionManager.sendMessage(socket.getId(), errorResponse);
            return;
        }

        // 4. 카메라맨 정보 세팅 및 세션 상태를 연결됨으로 변경 (핸드셰이크 시 인증된 userId 포함)
        // 라이트 모드 참여자는 userId가 없어 cameraUserId=null, cameraMode=LIGHT_MODE로 처리
        Long cameraUserId = getUserId(socket);
        String cameraMode = "LITE".equals(socket.getAttributes().get("authType")) ? "LIGHT_MODE" : "APP";
        info.setCameraUserId(cameraUserId);
        info.setCameraSocketId(socket.getId());
        info.setStatus("CONNECTED");

        // 5. Redis에 세션 정보 저장 및 소켓 TTL 갱신
        saveSessionInfo(sessionCode, info);
        redisTemplate.opsForValue().set("socket:" + socket.getId(), sessionCode, 10, TimeUnit.MINUTES);
        // 디렉터 소켓의 TTL도 함께 갱신
        redisTemplate.expire("socket:" + info.getDirectorSocketId(), 10, TimeUnit.MINUTES);

        // 6. DB 세션 상태를 연결됨으로 동기화 (라이트 모드면 LIGHT_MODE, 아니면 APP)
        sessionService.joinSession(sessionCode, cameraUserId, cameraMode);

        // 7. 디렉터에게 카메라맨 입장 알림
        SignalingResponse notifyDirector = SignalingResponse.builder()
                .type("PEER_JOINED")
                .sessionCode(sessionCode)
                .build();
        relaySender.send(info.getDirectorSocketId(), notifyDirector);

        log.info("Peer joined session: {}", sessionCode);
    }

    /**
     * DEVICE_SPEC 메시지를 처리합니다.
     * 기기 카메라 스펙을 device_specs 테이블에 저장(Insert-only)한 뒤, 기존 정책대로 상대 기기에 relay합니다.
     * 연결 안정성을 우선하기 위해 DB 저장 로직은 try-catch로 격리하며, 저장 성공/실패와 무관하게 relay는 항상 수행됩니다.
     *
     * @param socket 기기 스펙을 보낸 클라이언트의 웹소켓 세션
     * @param msg    기기 스펙(role, 줌 배율, 해상도 등)이 담긴 DEVICE_SPEC 메시지
     */
    public void handleDeviceSpec(WebSocketSession socket, SignalingRequest msg) {
        // 1. 기기 스펙 DB 저장 (실패해도 relay를 막지 않도록 예외를 격리)
        try {
            deviceSpecService.saveDeviceSpec(
                    msg.sessionCode(),
                    msg.role(),
                    msg.maxZoom(),
                    msg.minZoom(),
                    msg.screenRatio(),
                    msg.maxResolution(),
                    msg.osType()
            );
        } catch (Exception e) {
            log.error("Failed to save device spec: sessionCode={}", msg.sessionCode(), e);
        }

        // 2. 기존 정책대로 상대 기기에 스펙 중계
        relay(socket, msg);
    }

    /**
     * 두 기기 간의 WebRTC 연결을 위한 미디어 정보(SDP, ICE Candidate) 및 카메라 제어 신호를 단순 중계(Relay)합니다.
     * 서버는 메시지 내용을 해석하지 않고, 보낸 사람의 반대편(디렉터 ↔ 카메라) 소켓으로 메시지를 그대로 전달합니다.
     *
     * @param socket 메시지를 보낸 클라이언트의 웹소켓 세션
     * @param msg    중계할 WebRTC 시그널링 데이터 또는 커스텀 제어 신호
     */
    public void relay(WebSocketSession socket, SignalingRequest msg) {
        // 1. 소켓ID로 세션 코드 조회
        String sessionCode = redisTemplate.opsForValue().get("socket:" + socket.getId());
        if (sessionCode == null) return;

        // 2. 세션 코드로 방 정보 조회
        SessionInfo info = getSessionInfo(sessionCode);
        if (info == null) return;

        // 3. 슬라이딩 세션: 메시지를 주고받을 때마다 방과 소켓의 수명을 10분으로 연장
        redisTemplate.expire("session:" + sessionCode, 10, TimeUnit.MINUTES);
        redisTemplate.expire("socket:" + info.getDirectorSocketId(), 10, TimeUnit.MINUTES);
        if (info.getCameraSocketId() != null) {
            redisTemplate.expire("socket:" + info.getCameraSocketId(), 10, TimeUnit.MINUTES);
        }
        // 라이트 모드 토큰도 세션과 함께 슬라이딩 갱신
        liteTokenService.refresh(info.getLiteToken());

        // 4. SDP/ICE 진단 로깅 — 방향을 판별해 candidate 타입/SDP 요약을 구조화 로깅
        boolean fromDirector = socket.getId().equals(info.getDirectorSocketId());
        String direction = fromDirector ? "director->camera" : "camera->director";
        diagnosticLogger.logRelayed(msg, sessionCode, direction);

        // 5. 보낸 사람의 반대편 소켓으로 메시지 중계 (다른 인스턴스면 Redis 발행으로 자동 처리)
        String targetSocketId = fromDirector
                ? info.getCameraSocketId()
                : info.getDirectorSocketId();

        relaySender.send(targetSocketId, msg);
    }

    /**
     * 네트워크 오류나 브라우저 종료 등으로 인해 웹소켓 연결이 비정상적으로 끊어졌을 때 호출됩니다.
     * 세션에 남아있는 상대방에게 연결 끊김(PEER_DISCONNECTED)을 알리고, Redis에서 세션 정보를 정리합니다.
     *
     * @param socket 연결이 끊어진 클라이언트의 웹소켓 세션
     */
    public void handleDisconnect(WebSocketSession socket) {
        // 1. 소켓ID로 세션 코드 조회
        String sessionCode = redisTemplate.opsForValue().get("socket:" + socket.getId());
        if (sessionCode == null) return;

        // 2. 세션에 상대방이 남아있으면 연결 끊김을 알림
        SessionInfo info = getSessionInfo(sessionCode);
        if (info != null) {
            String targetSocketId = socket.getId().equals(info.getDirectorSocketId())
                    ? info.getCameraSocketId()
                    : info.getDirectorSocketId();

            SignalingResponse disconnectMsg = SignalingResponse.builder()
                    .type("PEER_DISCONNECTED")
                    .sessionCode(sessionCode)
                    .build();

            relaySender.send(targetSocketId, disconnectMsg);

            // 라이트 모드 촬영자가 이탈한 경우: 세션을 종료하지 않고 재연결 대기 상태로 되돌림
            // (토큰은 유효하게 유지 → 같은 토큰으로 재접속 가능, F-CON-06 자동 재연결 대응)
            boolean isLiteCameraDrop = "LITE".equals(socket.getAttributes().get("authType"))
                    && socket.getId().equals(info.getCameraSocketId());

            if (isLiteCameraDrop) {
                info.setCameraSocketId(null);
                info.setCameraUserId(null);
                info.setStatus("WAITING");
                saveSessionInfo(sessionCode, info);
                log.info("Lite camera disconnected, session kept for reconnect: {}", sessionCode);
            } else {
                // 디렉터 이탈 또는 일반(로그인) 촬영자 이탈: 세션 종료 + 라이트 토큰 무효화
                info.setStatus("ENDED");
                saveSessionInfo(sessionCode, info);
                sessionService.endSession(sessionCode);
                liteTokenService.invalidate(info.getLiteToken());
                log.info("Session ended due to disconnect: {}", sessionCode);
            }
        }

        // 3. 끊어진 소켓의 세션 매핑 정보 삭제
        redisTemplate.delete("socket:" + socket.getId());
    }

    /**
     * 클라이언트가 명시적으로 세션 종료(촬영 종료)를 요청했을 때 호출됩니다.
     * 방 상태를 종료(ENDED)로 처리하고 상대방에게 종료 알림(SESSION_ENDED)을 전송합니다.
     *
     * @param socket 세션 종료를 요청한 클라이언트의 웹소켓 세션
     * @param msg    세션 코드가 담긴 종료 요청 메시지
     */
    public void endSession(WebSocketSession socket, SignalingRequest msg) {
        // 1. 세션 코드로 방 정보 조회
        String sessionCode = msg.sessionCode();
        SessionInfo info = getSessionInfo(sessionCode);
        if (info != null) {
            // 2. 세션 상태를 종료로 변경
            info.setStatus("ENDED");
            saveSessionInfo(sessionCode, info);

            // 3. DB 세션도 종료 상태로 동기화 + 라이트 토큰 무효화 (세션 종료 후 만료)
            sessionService.endSession(sessionCode);
            liteTokenService.invalidate(info.getLiteToken());

            // 4. 상대방에게 세션 종료 알림 전송
            String targetSocketId = socket.getId().equals(info.getDirectorSocketId())
                    ? info.getCameraSocketId()
                    : info.getDirectorSocketId();

            SignalingResponse endMsg = SignalingResponse.builder()
                    .type("SESSION_ENDED")
                    .sessionCode(sessionCode)
                    .build();
            relaySender.send(targetSocketId, endMsg);
        }
        // 5. 소켓의 세션 매핑 정보 삭제
        redisTemplate.delete("socket:" + socket.getId());
        log.info("Session ended: {}", sessionCode);
    }

    /**
     * Redis에서 세션 코드에 해당하는 방 정보(SessionInfo)를 조회하여 객체로 반환합니다.
     *
     * @param sessionCode 조회할 8자리 세션 코드
     * @return 파싱된 SessionInfo 객체 (없거나 파싱 실패 시 null)
     */
    private SessionInfo getSessionInfo(String sessionCode) {
        String raw = redisTemplate.opsForValue().get("session:" + sessionCode);
        if (raw == null) return null;
        try {
            return objectMapper.readValue(raw, SessionInfo.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse SessionInfo JSON", e);
            return null;
        }
    }

    /**
     * SessionInfo 객체를 JSON 문자열로 직렬화하여 Redis에 저장합니다.
     * 모든 세션은 기본적으로 10분의 TTL(유효기간)을 가집니다.
     *
     * @param sessionCode 저장할 방의 세션 코드 (Key)
     * @param info        저장할 세션 상태 객체 (Value)
     */
    private void saveSessionInfo(String sessionCode, SessionInfo info) {
        try {
            redisTemplate.opsForValue().set(
                    "session:" + sessionCode,
                    objectMapper.writeValueAsString(info),
                    10, TimeUnit.MINUTES
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SessionInfo", e);
        }
    }

    /**
     * 웹소켓 핸드셰이크 시 소켓 attributes에 저장된 인증 유저 ID를 추출합니다.
     *
     * @param socket 유저 ID를 추출할 웹소켓 세션
     * @return 인증된 유저 ID (없으면 null)
     */
    private Long getUserId(WebSocketSession socket) {
        return (Long) socket.getAttributes().get("userId");
    }
}
