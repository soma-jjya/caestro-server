package com.caestro.server.domain.signaling.service;

import com.caestro.server.domain.devicespec.service.DeviceSpecService;
import com.caestro.server.domain.session.service.SessionService;
import com.caestro.server.domain.signaling.dto.request.SignalingRequest;
import com.caestro.server.domain.signaling.dto.response.SignalingResponse;
import com.caestro.server.domain.signaling.entity.SessionInfo;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
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

    private static final char[] CODE_ALPHABET =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int CODE_LENGTH = 6;
    private static final int CODE_GENERATION_MAX_ATTEMPTS = 5;
    private static final long SESSION_TTL_SECONDS = 600;

    // JOIN 참여자 슬롯 원자 획득 스크립트 (#73) — 검사+기록을 Redis 안에서 단일 단위로 수행
    private static final RedisScript<String> JOIN_SLOT_CLAIM_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/join_slot_claim.lua"), String.class);

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 방을 만든 사람(owner)이 새로운 WebRTC 세션을 생성합니다.
     * 세션 코드를 발급하고 Redis에 대기(WAITING) 상태로 저장한 뒤 클라이언트에게 코드를 반환합니다.
     * owner가 시작 디렉터가 되며(currentDirectorUserId = ownerUserId), 이후 SWAP_ROLE로 역할을 교체할 수 있습니다.
     *
     * @param socket owner의 웹소켓 세션 객체
     * @param msg    클라이언트로부터 받은 세션 생성 요청 메시지
     */
    public void createSession(WebSocketSession socket, SignalingRequest msg) {
        // 1. 세션 코드 발급
        String sessionCode = generateUniqueSessionCode();

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
     * 세션 코드로 방에 입장하거나, 네트워크 전환 등으로 끊긴 뒤 재연결(슬롯 인계)합니다.
     * 세션 정체성은 소켓이 아니라 userId(JWT, 게스트 포함)를 기준으로 판단합니다.
     * - 요청 userId가 기존 owner/participant 슬롯의 주인과 같으면: 소켓만 교체하고 재연결로 처리(takeover).
     * - 슬롯이 비어 있고 신규 사용자면: 참여자로 입장.
     * - 이미 다른 사용자가 연결된 세션이면: 거부.
     *
     * @param socket 입장/재연결하는 클라이언트의 웹소켓 세션 객체
     * @param msg    입장할 세션 코드(sessionCode)가 담긴 요청 메시지
     */
    public void joinSession(WebSocketSession socket, SignalingRequest msg) {
        // 1. 세션 코드 정규화(수동 입력 대비) 및 사용자 확인
        String sessionCode = normalizeSessionCode(msg.sessionCode());
        Long userId = getUserId(socket);
        if (userId == null) {
            sendError(socket, sessionCode, ErrorCode.INVALID_SIGNALING_MESSAGE);
            return;
        }

        // 2. 참여자 슬롯 획득 — 검사와 기록을 Lua로 원자화해 동시 JOIN 경합을 차단(#73)
        String result = redisTemplate.execute(JOIN_SLOT_CLAIM_SCRIPT,
                List.of("session:" + sessionCode),
                String.valueOf(userId), socket.getId(), String.valueOf(SESSION_TTL_SECONDS));

        // 3. 스크립트 판정 결과에 따라 분기
        switch (result == null ? "" : result) {
            case "NOT_FOUND" -> sendError(socket, sessionCode, ErrorCode.SESSION_NOT_FOUND);
            case "OCCUPIED" -> sendError(socket, sessionCode, ErrorCode.SESSION_ALREADY_CONNECTED);
            case "TAKEOVER_OWNER", "TAKEOVER_PARTICIPANT" -> {
                // 재연결(takeover): 본인 슬롯이면 소켓만 교체하고 복귀 처리
                SessionInfo info = getSessionInfo(sessionCode);
                if (info == null) {
                    sendError(socket, sessionCode, ErrorCode.SESSION_NOT_FOUND);
                    return;
                }
                reconnectToSlot(socket, sessionCode, info, "TAKEOVER_OWNER".equals(result));
            }
            case "CLAIMED" -> completeJoin(socket, sessionCode, userId);
            default -> sendError(socket, sessionCode, ErrorCode.INVALID_SIGNALING_MESSAGE);
        }
    }

    /**
     * 슬롯 획득(CLAIMED) 이후의 신규 참여자 입장 마무리.
     * 소켓 매핑 등록, owner 소켓 TTL 연장, DB 동기화, 방장에게 입장 알림을 수행한다.
     *
     * @param socket      참여자의 웹소켓 세션
     * @param sessionCode 입장한 세션 코드
     * @param userId      참여자 userId
     */
    private void completeJoin(WebSocketSession socket, String sessionCode, Long userId) {
        // 1. 소켓-세션 매핑 등록
        redisTemplate.opsForValue().set("socket:" + socket.getId(), sessionCode, 10, TimeUnit.MINUTES);

        // 2. owner 소켓 TTL 연장 및 입장 알림 (스크립트가 기록한 최신 상태 재조회)
        SessionInfo info = getSessionInfo(sessionCode);
        String ownerSocketId = info != null ? info.getOwnerSocketId() : null;
        if (ownerSocketId != null) {
            redisTemplate.expire("socket:" + ownerSocketId, 10, TimeUnit.MINUTES);
        }

        // 3. DB 세션 상태를 연결됨으로 동기화
        sessionService.joinSession(sessionCode, userId, "APP");

        // 4. owner에게 참여자 입장 알림
        SignalingResponse notifyOwner = SignalingResponse.builder()
                .type("PEER_JOINED")
                .sessionCode(sessionCode)
                .build();
        relaySender.send(ownerSocketId, notifyOwner);

        log.info("Peer joined session: {}", sessionCode);
    }

    /**
     * 같은 userId의 재연결을 처리합니다(슬롯 인계, takeover).
     * 기존 슬롯의 socketId를 새 소켓으로 교체하고, 옛 소켓의 매핑을 제거해
     * 뒤늦게 죽는 유령 소켓의 disconnect가 복구된 세션을 훼손하지 못하게 합니다.
     * 재연결한 쪽에는 현재 상태 복원용 SESSION_RESUMED를, 상대에게는 PEER_RECONNECTED를 통지합니다.
     *
     * @param socket      재연결한 클라이언트의 새 웹소켓 세션
     * @param sessionCode 대상 세션 코드
     * @param info        현재 세션 상태
     * @param isOwner     재연결 주체가 owner 슬롯이면 true, participant 슬롯이면 false
     */
    private void reconnectToSlot(WebSocketSession socket, String sessionCode, SessionInfo info, boolean isOwner) {
        // 1. 옛 소켓 매핑 제거 — 뒤늦게 끊기는 유령 소켓이 handleDisconnect로 세션을 종료/훼손하는 것 방지
        String oldSocketId = isOwner ? info.getOwnerSocketId() : info.getParticipantSocketId();
        if (oldSocketId != null) {
            redisTemplate.delete("socket:" + oldSocketId);
        }

        // 2. 슬롯의 socketId만 새 소켓으로 교체 (정체성=userId는 유지, "전화선"만 갱신)
        if (isOwner) {
            info.setOwnerSocketId(socket.getId());
        } else {
            info.setParticipantSocketId(socket.getId());
        }
        // 두 슬롯이 모두 사용자로 차 있으면 CONNECTED, 아니면(상대가 아직 유예/부재) WAITING으로 정정
        boolean bothPresent = info.getOwnerUserId() != null && info.getParticipantUserId() != null;
        info.setStatus(bothPresent ? "CONNECTED" : "WAITING");
        saveSessionInfo(sessionCode, info);

        // 3. 새 소켓 매핑 등록 + 상대 슬롯 TTL 갱신
        redisTemplate.opsForValue().set("socket:" + socket.getId(), sessionCode, 10, TimeUnit.MINUTES);
        String peerSocketId = isOwner ? info.getParticipantSocketId() : info.getOwnerSocketId();
        if (peerSocketId != null) {
            redisTemplate.expire("socket:" + peerSocketId, 10, TimeUnit.MINUTES);
        }

        // 4. 재연결한 쪽에 현재 상태(디렉터 포인터)를 담아 SESSION_RESUMED 전송 (화면 복원용)
        SignalingResponse resumed = SignalingResponse.builder()
                .type("SESSION_RESUMED")
                .sessionCode(sessionCode)
                .currentDirectorUserId(info.getCurrentDirectorUserId())
                .build();
        sessionManager.sendMessage(socket.getId(), resumed);

        // 5. 상대가 남아 있으면 재연결 알림 (신규 입장 PEER_JOINED와 구분)
        if (peerSocketId != null) {
            SignalingResponse peerNotify = SignalingResponse.builder()
                    .type("PEER_RECONNECTED")
                    .sessionCode(sessionCode)
                    .build();
            relaySender.send(peerSocketId, peerNotify);
        }

        log.info("Reconnected to session {} as {}", sessionCode, isOwner ? "owner" : "participant");
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
                    normalizeSessionCode(msg.sessionCode()),
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
            boolean isOwnerDrop = socket.getId().equals(info.getOwnerSocketId());
            boolean isParticipantDrop = socket.getId().equals(info.getParticipantSocketId());

            // 유령 소켓 가드: 어느 슬롯과도 일치하지 않으면(takeover로 이미 교체된 옛 소켓)
            // 세션을 건드리지 않고 매핑만 정리한다. 없으면 "참여자 아니면 방장" 이분법에 걸려 오분류된다.
            if (!isOwnerDrop && !isParticipantDrop) {
                redisTemplate.delete("socket:" + socket.getId());
                log.info("Stale socket disconnect ignored: {} (session {})", socket.getId(), sessionCode);
                return;
            }

            String targetSocketId = isOwnerDrop
                    ? info.getParticipantSocketId()
                    : info.getOwnerSocketId();

            SignalingResponse disconnectMsg = SignalingResponse.builder()
                    .type("PEER_DISCONNECTED")
                    .sessionCode(sessionCode)
                    .build();

            relaySender.send(targetSocketId, disconnectMsg);

            // 참여자가 이탈한 경우: 세션을 종료하지 않고 재연결 대기(WAITING)로 되돌림
            if (isParticipantDrop) {
                info.setParticipantSocketId(null);
                info.setParticipantUserId(null);
                info.setCurrentDirectorUserId(info.getOwnerUserId()); // 참여자가 나갔으니 디렉터는 owner로 복귀
                info.setStatus("WAITING");
                saveSessionInfo(sessionCode, info);
                log.info("Participant disconnected, session kept for reconnect: {}", sessionCode);
            } else {
                // 방장 이탈: 즉시 종료하지 않고 재연결을 유예한다(Redis TTL 동안 대기).
                // ownerUserId는 유지해, 같은 방장이 JOIN_SESSION으로 슬롯을 인계(takeover)받을 수 있게 한다.
                // 유예 안에 돌아오지 않으면 세션은 TTL로 자연 만료된다.
                info.setOwnerSocketId(null);
                info.setStatus("WAITING");
                saveSessionInfo(sessionCode, info);
                log.info("Owner disconnected, session kept for reconnect (grace via TTL): {}", sessionCode);
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
     * 하트비트(PING)를 처리한다.
     * 연결 유지를 위해 PONG으로 응답하고, 세션에 소속된 소켓이면 Redis TTL을 연장해
     * 조용한 촬영 중에도 방/소켓 상태가 만료되지 않게 한다.
     *
     * @param socket PING을 보낸 클라이언트의 웹소켓 세션
     */
    public void handlePing(WebSocketSession socket) {
        // 세션에 소속된 소켓이면 방·소켓 TTL을 함께 연장 (조용한 세션의 조기 만료 방지)
        String sessionCode = redisTemplate.opsForValue().get("socket:" + socket.getId());
        if (sessionCode != null) {
            redisTemplate.expire("socket:" + socket.getId(), 10, TimeUnit.MINUTES);
            redisTemplate.expire("session:" + sessionCode, 10, TimeUnit.MINUTES);
        }

        // 연결 유지를 위해 PONG 응답
        SignalingResponse pong = SignalingResponse.builder()
                .type("PONG")
                .build();
        sessionManager.sendMessage(socket.getId(), pong);
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
     * 활성 세션과 충돌하지 않는 짧은 세션 코드를 발급한다.
     * Crockford Base32 알파벳으로 6자를 SecureRandom으로 생성하고, Redis에 동일 코드가 있으면 재시도한다.
     *
     * @return 활성 세션 기준으로 유일한 6자 세션 코드
     * @throws CustomException SESSION_CODE_GENERATION_FAILED - 최대 재시도 내 유일 코드 발급 실패
     */
    private String generateUniqueSessionCode() {
        for (int attempt = 0; attempt < CODE_GENERATION_MAX_ATTEMPTS; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)]);
            }
            String code = sb.toString();

            // 활성 세션(session:{code})에 없을 때만 채택
            if (Boolean.FALSE.equals(redisTemplate.hasKey("session:" + code))) {
                return code;
            }
        }
        throw new CustomException(ErrorCode.SESSION_CODE_GENERATION_FAILED);
    }

    /**
     * 사용자가 수동 입력한 세션 코드를 정규화한다. (앞뒤 공백 제거 + 대문자 변환)
     * QR/텍스트 어느 경로로 들어오든 발급 시 형식(대문자)과 일치시키기 위함이다.
     *
     * @param rawCode 클라이언트가 보낸 원본 세션 코드 (null 가능)
     * @return 정규화된 코드 (입력이 null이면 null)
     */
    private String normalizeSessionCode(String rawCode) {
        return rawCode == null ? null : rawCode.trim().toUpperCase();
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
