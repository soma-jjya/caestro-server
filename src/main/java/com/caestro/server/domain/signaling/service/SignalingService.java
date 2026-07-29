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
    private final SignalingDiagnosticLogger diagnosticLogger;
    private final SignalingRelaySender relaySender;

    /**
     * 방을 만든 사람(owner)이 새로운 WebRTC 세션을 생성합니다.
     * 세션 코드를 발급하고 Redis에 대기(WAITING) 상태로 저장한 뒤 클라이언트에게 코드를 반환합니다.
     * owner가 시작 디렉터가 되며(currentDirectorUserId = ownerUserId), 이후 SWAP_ROLE로 역할을 교체할 수 있습니다.
     *
     * @param socket owner의 웹소켓 세션 객체
     * @param msg    클라이언트로부터 받은 세션 생성 요청 메시지
     */
    public void createSession(WebSocketSession socket, SignalingRequest msg) {
        // 1. 세션 코드 발급 (초대 링크의 비밀값 역할도 겸하므로 충분한 엔트로피 확보: 16 hex = 64bit)
        String sessionCode = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 2. 세션 정보 생성 및 owner(생성자) 세팅. 생성자가 시작 디렉터가 된다.
        Long ownerUserId = getUserId(socket);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
        SessionInfo info = new SessionInfo();
        info.setSessionCode(sessionCode);
        info.setOwnerUserId(ownerUserId);
        info.setOwnerSocketId(socket.getId());
        info.setCurrentDirectorUserId(ownerUserId);
        info.setStatus("WAITING");
        info.setExpiresAt(expiresAt);

        // 3. Redis에 세션 정보 및 소켓-세션 매핑 저장
        saveSessionInfo(sessionCode, info);
        redisTemplate.opsForValue().set("socket:" + socket.getId(), sessionCode, 10, TimeUnit.MINUTES);

        // 4. DB에 세션 영구 저장 (Redis는 실시간 상태, DB는 영구 기록 용도)
        sessionService.createSession(sessionCode, ownerUserId, expiresAt);

        // 5. owner에게 세션 생성 완료 응답 전송 (참여자는 이 sessionCode로 입장)
        SignalingResponse response = SignalingResponse.builder()
                .type("SESSION_CREATED")
                .sessionCode(sessionCode)
                .build();

        sessionManager.sendMessage(socket.getId(), response);
        log.info("Session created: {}", sessionCode);
    }

    /**
     * 참여자(participant)가 발급된 세션 코드로 기존 방에 입장합니다.
     * 방이 존재하면 상태를 연결됨(CONNECTED)으로 변경하고, owner에게 입장을 알립니다.
     *
     * @param socket 참여자의 웹소켓 세션 객체
     * @param msg    입장할 세션 코드(sessionCode)가 담긴 요청 메시지
     */
    public void joinSession(WebSocketSession socket, SignalingRequest msg) {
        // 1. 세션 코드로 방 정보 조회
        String sessionCode = msg.sessionCode();
        SessionInfo info = getSessionInfo(sessionCode);

        // 2. 존재하지 않는 세션이면 에러 응답
        if (info == null) {
            sendError(socket, sessionCode, ErrorCode.SESSION_NOT_FOUND);
            return;
        }

        // 3. 이미 참여자가 연결된 세션이면 에러 응답
        if ("CONNECTED".equals(info.getStatus())) {
            sendError(socket, sessionCode, ErrorCode.SESSION_ALREADY_CONNECTED);
            return;
        }

        // 4. 참여자 정보 세팅 및 세션 상태를 연결됨으로 변경 (참여자도 JWT(게스트 포함)라 userId가 항상 존재)
        Long participantUserId = getUserId(socket);
        String cameraMode = "APP";
        info.setParticipantUserId(participantUserId);
        info.setParticipantSocketId(socket.getId());
        info.setStatus("CONNECTED");

        // 5. Redis에 세션 정보 저장 및 소켓 TTL 갱신
        saveSessionInfo(sessionCode, info);
        redisTemplate.opsForValue().set("socket:" + socket.getId(), sessionCode, 10, TimeUnit.MINUTES);
        // owner 소켓의 TTL도 함께 갱신
        redisTemplate.expire("socket:" + info.getOwnerSocketId(), 10, TimeUnit.MINUTES);

        // 6. DB 세션 상태를 연결됨으로 동기화
        sessionService.joinSession(sessionCode, participantUserId, cameraMode);

        // 7. owner에게 참여자 입장 알림
        SignalingResponse notifyOwner = SignalingResponse.builder()
                .type("PEER_JOINED")
                .sessionCode(sessionCode)
                .build();
        relaySender.send(info.getOwnerSocketId(), notifyOwner);

        log.info("Peer joined session: {}", sessionCode);
    }

    /**
     * 역할 교체(SWAP_ROLE)를 처리합니다.
     * owner/participant 슬롯(정체성)은 그대로 두고, "지금 디렉터"를 가리키는 currentDirectorUserId만
     * owner↔participant로 토글합니다. 두 참여자 모두에게 새 디렉터를 통지합니다.
     *
     * @param socket 역할 교체를 요청한 클라이언트의 웹소켓 세션
     * @param msg    세션 코드가 담긴 요청 메시지
     */
    public void swapRoles(WebSocketSession socket, SignalingRequest msg) {
        // 1. 소켓ID로 세션 코드 조회
        String sessionCode = redisTemplate.opsForValue().get("socket:" + socket.getId());
        if (sessionCode == null) return;

        SessionInfo info = getSessionInfo(sessionCode);
        if (info == null) return;

        // 2. 두 명이 모두 연결된 상태에서만 교체 가능
        if (!"CONNECTED".equals(info.getStatus()) || info.getParticipantUserId() == null) {
            sendError(socket, sessionCode, ErrorCode.SESSION_NOT_CONNECTED);
            return;
        }

        // 3. 역할 포인터 토글 (슬롯은 그대로)
        Long newDirector = info.getCurrentDirectorUserId().equals(info.getOwnerUserId())
                ? info.getParticipantUserId()
                : info.getOwnerUserId();
        info.setCurrentDirectorUserId(newDirector);
        saveSessionInfo(sessionCode, info);

        // 4. 양쪽 모두에게 새 디렉터 통지
        SignalingResponse swapped = SignalingResponse.builder()
                .type("ROLE_SWAPPED")
                .sessionCode(sessionCode)
                .currentDirectorUserId(newDirector)
                .build();
        relaySender.send(info.getOwnerSocketId(), swapped);
        relaySender.send(info.getParticipantSocketId(), swapped);

        log.info("Role swapped in session {}: currentDirector={}", sessionCode, newDirector);
    }

    /**
     * DEVICE_SPEC 메시지를 처리합니다.
     * 기기 카메라 스펙을 device_specs 테이블에 저장한 뒤, 기존 정책대로 상대 기기에 relay합니다.
     * 스펙은 기기(사람)의 속성이므로 인증된 userId를 기준으로 저장합니다(역할 교체와 무관).
     * 연결 안정성을 우선하기 위해 DB 저장 로직은 try-catch로 격리하며, 저장 성공/실패와 무관하게 relay는 항상 수행됩니다.
     *
     * @param socket 기기 스펙을 보낸 클라이언트의 웹소켓 세션
     * @param msg    기기 스펙(줌 배율, 해상도 등)이 담긴 DEVICE_SPEC 메시지
     */
    public void handleDeviceSpec(WebSocketSession socket, SignalingRequest msg) {
        // 1. 기기 스펙 DB 저장 (실패해도 relay를 막지 않도록 예외를 격리)
        //    role은 클라이언트 주장을 믿지 않고, 인증된 userId를 키로 사용한다.
        try {
            Long userId = getUserId(socket);
            deviceSpecService.saveDeviceSpec(
                    msg.sessionCode(),
                    userId,
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
     * 두 기기 간의 WebRTC 미디어 정보(SDP, ICE) 및 제어 신호를 단순 중계(Relay)합니다.
     * 서버는 내용을 해석하지 않고, 보낸 사람의 반대편 엔드포인트(owner ↔ participant)로 전달합니다.
     * 라우팅은 역할이 아니라 정체성(어느 소켓 슬롯인지)으로 판단하므로 역할 교체와 무관하게 동작합니다.
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
        redisTemplate.expire("socket:" + info.getOwnerSocketId(), 10, TimeUnit.MINUTES);
        if (info.getParticipantSocketId() != null) {
            redisTemplate.expire("socket:" + info.getParticipantSocketId(), 10, TimeUnit.MINUTES);
        }

        // 4. SDP/ICE 진단 로깅 — 어느 엔드포인트가 보냈는지(정체성 기준) 판별해 구조화 로깅
        boolean fromOwner = socket.getId().equals(info.getOwnerSocketId());
        String direction = fromOwner ? "owner->participant" : "participant->owner";
        diagnosticLogger.logRelayed(msg, sessionCode, direction);

        // 5. 보낸 사람의 반대편 소켓으로 메시지 중계 (다른 인스턴스면 Redis 발행으로 자동 처리)
        String targetSocketId = fromOwner
                ? info.getParticipantSocketId()
                : info.getOwnerSocketId();

        relaySender.send(targetSocketId, msg);
    }

    /**
     * 웹소켓 연결이 비정상적으로 끊겼을 때 호출됩니다.
     * 남은 상대방에게 연결 끊김(PEER_DISCONNECTED)을 알리고 Redis 상태를 정리합니다.
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
            String targetSocketId = socket.getId().equals(info.getOwnerSocketId())
                    ? info.getParticipantSocketId()
                    : info.getOwnerSocketId();

            SignalingResponse disconnectMsg = SignalingResponse.builder()
                    .type("PEER_DISCONNECTED")
                    .sessionCode(sessionCode)
                    .build();

            relaySender.send(targetSocketId, disconnectMsg);

            // 참여자가 이탈한 경우: 세션을 종료하지 않고 재연결 대기(WAITING)로 되돌림
            // (원래 참여자만 슬롯을 재획득하도록 묶는 강화는 후속 Redis 작업에서 처리)
            boolean isParticipantDrop = socket.getId().equals(info.getParticipantSocketId());

            if (isParticipantDrop) {
                info.setParticipantSocketId(null);
                info.setParticipantUserId(null);
                info.setCurrentDirectorUserId(info.getOwnerUserId()); // 참여자가 나갔으니 디렉터는 owner로 복귀
                info.setStatus("WAITING");
                saveSessionInfo(sessionCode, info);
                log.info("Participant disconnected, session kept for reconnect: {}", sessionCode);
            } else {
                // owner 이탈: 세션 종료 (방의 주인이 나가면 방을 닫는다)
                info.setStatus("ENDED");
                saveSessionInfo(sessionCode, info);
                sessionService.endSession(sessionCode);
                log.info("Session ended due to disconnect: {}", sessionCode);
            }
        }

        // 3. 끊어진 소켓의 세션 매핑 정보 삭제
        redisTemplate.delete("socket:" + socket.getId());
    }

    /**
     * 클라이언트가 명시적으로 세션 종료를 요청했을 때 호출됩니다.
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

            // 3. DB 세션도 종료 상태로 동기화
            sessionService.endSession(sessionCode);

            // 4. 상대방에게 세션 종료 알림 전송
            String targetSocketId = socket.getId().equals(info.getOwnerSocketId())
                    ? info.getParticipantSocketId()
                    : info.getOwnerSocketId();

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
     * 에러 응답을 보낸 클라이언트에게 직접 전송한다.
     */
    private void sendError(WebSocketSession socket, String sessionCode, ErrorCode errorCode) {
        SignalingResponse errorResponse = SignalingResponse.builder()
                .type("ERROR")
                .sessionCode(sessionCode)
                .message(errorCode.getMessage())
                .build();
        sessionManager.sendMessage(socket.getId(), errorResponse);
    }

    /**
     * Redis에서 세션 코드에 해당하는 방 정보(SessionInfo)를 조회하여 객체로 반환합니다.
     *
     * @param sessionCode 조회할 세션 코드
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
     * SessionInfo 객체를 JSON 문자열로 직렬화하여 Redis에 저장합니다. (기본 TTL 10분)
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
