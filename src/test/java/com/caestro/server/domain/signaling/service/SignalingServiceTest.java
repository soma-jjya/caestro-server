package com.caestro.server.domain.signaling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.devicespec.service.DeviceSpecService;
import com.caestro.server.domain.session.service.SessionService;
import com.caestro.server.domain.signaling.entity.SessionInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.WebSocketSession;

/**
 * handleDisconnect의 소켓-슬롯 일치 검증 테스트.
 * takeover로 이미 교체된 옛 소켓(유령)의 늦은 disconnect가 방장 이탈로 오분류되어
 * 세션을 훼손하던 결함(#73)의 재현·방어를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SignalingServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private WebSocketSessionManager sessionManager;

    @Mock
    private SessionService sessionService;

    @Mock
    private DeviceSpecService deviceSpecService;

    @Mock
    private SignalingDiagnosticLogger diagnosticLogger;

    @Mock
    private SignalingRelaySender relaySender;

    @Mock
    private SignalingMetrics metrics;

    @Mock
    private SessionRecordDispatcher recordDispatcher;

    @Mock
    private com.caestro.server.global.ratelimit.RedisRateLimiter rateLimiter;

    @Mock
    private WebSocketSession socket;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SignalingService signalingService;

    private static final String SESSION_CODE = "K7P2QM";
    private static final String OWNER_SOCKET = "owner-sock";
    private static final String PARTICIPANT_SOCKET = "participant-sock";

    @BeforeEach
    void setUp() {
        signalingService = new SignalingService(
                redisTemplate, sessionManager, objectMapper,
                sessionService, deviceSpecService, diagnosticLogger, relaySender, metrics, recordDispatcher,
                rateLimiter);
        // 일부 경로(OCCUPIED 등)는 opsForValue를 쓰지 않으므로 lenient로 스텁
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 레이트리밋은 기본 허용으로 스텁 — 제한 동작은 전용 테스트에서 검증 (#125)
        lenient().when(rateLimiter.tryAcquire(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(true);
        // 기록 디스패처는 단위 테스트에서 동기 실행으로 대체 — 기존 DB 호출 검증(verify)을 그대로 유지한다
        lenient().doAnswer(inv -> {
            inv.getArgument(2, Runnable.class).run();
            return null;
        }).when(recordDispatcher).dispatch(any(), any(), any(Runnable.class));
    }

    /** 두 슬롯이 모두 찬 세션을 주어진 상태로 Redis 모킹에 심는다. */
    private void givenSessionWithStatus(String disconnectingSocketId, String status) throws Exception {
        SessionInfo info = new SessionInfo();
        info.setSessionCode(SESSION_CODE);
        info.setOwnerUserId(1L);
        info.setOwnerSocketId(OWNER_SOCKET);
        info.setParticipantUserId(2L);
        info.setParticipantSocketId(PARTICIPANT_SOCKET);
        info.setCurrentDirectorUserId(1L);
        info.setStatus(status);

        given(socket.getId()).willReturn(disconnectingSocketId);
        given(valueOperations.get("socket:" + disconnectingSocketId)).willReturn(SESSION_CODE);
        given(valueOperations.get("session:" + SESSION_CODE))
                .willReturn(objectMapper.writeValueAsString(info));
    }

    private void givenConnectedSession(String disconnectingSocketId) throws Exception {
        givenSessionWithStatus(disconnectingSocketId, "CONNECTED");
    }

    @Test
    @DisplayName("유령 소켓(어느 슬롯과도 불일치)의 disconnect는 세션을 훼손하지 않고 매핑만 정리한다")
    void handleDisconnect_ghostSocket_doesNotTouchSession() throws Exception {
        // 유령: takeover로 이미 슬롯에서 교체된 옛 소켓
        givenConnectedSession("ghost-sock");

        signalingService.handleDisconnect(socket);

        verify(valueOperations, never()).set(startsWith("session:"), any(), anyLong(), any()); // 세션 미변경
        verify(relaySender, never()).send(any(), any());                                      // 가짜 알림 없음
        verify(redisTemplate).delete("socket:ghost-sock");                                    // 매핑만 정리
    }

    @Test
    @DisplayName("종료된(ENDED) 세션의 늦은 disconnect는 세션을 부활시키지 않고 매핑만 정리한다")
    void handleDisconnect_endedSession_cleansMappingOnly() throws Exception {
        // END_SESSION 처리 뒤 뒤늦게 닫히는 소켓 — 슬롯과 일치하더라도 죽은 세션을 건드리면 안 된다.
        // 방치 시: 이미 닫힌 상대에게 PEER_DISCONNECTED 발행(수신자 0) + ENDED가 WAITING으로 부활(#93)
        givenSessionWithStatus(PARTICIPANT_SOCKET, "ENDED");

        signalingService.handleDisconnect(socket);

        verify(relaySender, never()).send(any(), any());                                       // 유령 알림 없음
        verify(valueOperations, never()).set(startsWith("session:"), any(), anyLong(), any()); // 부활 없음
        verify(redisTemplate).delete("socket:" + PARTICIPANT_SOCKET);                          // 매핑만 정리
    }

    @Test
    @DisplayName("드레인 종료(#106)는 슬롯·상태·상대 통지를 건드리지 않고 소켓 매핑만 지운다 → 재접속이 takeover가 된다")
    void handleDrainDisconnect_keepsSessionForTakeover() throws Exception {
        // 참여자 소켓이 드레인으로 닫힘 — 일반 이탈이면 participant 슬롯이 즉시 해제돼 재접속이 신규 입장이 된다
        given(socket.getId()).willReturn(PARTICIPANT_SOCKET);
        given(valueOperations.get("socket:" + PARTICIPANT_SOCKET)).willReturn(SESSION_CODE);

        signalingService.handleDrainDisconnect(socket);

        verify(redisTemplate).delete("socket:" + PARTICIPANT_SOCKET);                          // 매핑만 정리
        verify(valueOperations, never()).set(startsWith("session:"), any(), anyLong(), any()); // 슬롯·상태 불변
        verify(relaySender, never()).send(any(), any());                                       // PEER_DISCONNECTED 없음
    }

    @Test
    @DisplayName("JOIN: 스크립트가 CLAIMED를 반환하면 매핑 등록·DB 동기화·PEER_JOINED 알림을 수행한다")
    void joinSession_claimed_completesJoin() throws Exception {
        SessionInfo info = new SessionInfo();
        info.setOwnerUserId(1L);
        info.setOwnerSocketId(OWNER_SOCKET);
        info.setParticipantUserId(2L);
        info.setStatus("CONNECTED");
        given(socket.getId()).willReturn("p-sock");
        given(socket.getAttributes()).willReturn(java.util.Map.of("userId", 2L));
        given(redisTemplate.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<String>>any(),
                org.mockito.ArgumentMatchers.anyList(), any(), any(), any())).willReturn("CLAIMED");
        given(valueOperations.get("session:" + SESSION_CODE))
                .willReturn(objectMapper.writeValueAsString(info));

        signalingService.joinSession(socket, joinRequest(SESSION_CODE));

        verify(valueOperations).set(eq("socket:p-sock"), eq(SESSION_CODE), anyLong(), any(TimeUnit.class));
        verify(redisTemplate).expire(eq("socket:" + OWNER_SOCKET), anyLong(), any(TimeUnit.class));
        // 기록 태스크에 스냅샷(ownerUserId, expiresAt)이 동봉된다 (#124 — 순서 무관 upsert 재료)
        verify(sessionService).joinSession(SESSION_CODE, 2L, "APP", 1L, null);
        verify(relaySender).send(eq(OWNER_SOCKET), any());
        verify(metrics).countJoin("claimed");
    }

    @Test
    @DisplayName("JOIN: 스크립트가 OCCUPIED를 반환하면 에러만 보내고 아무것도 변경하지 않는다")
    void joinSession_occupied_sendsErrorOnly() {
        given(socket.getId()).willReturn("late-sock");
        given(socket.getAttributes()).willReturn(java.util.Map.of("userId", 3L));
        given(redisTemplate.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<String>>any(),
                org.mockito.ArgumentMatchers.anyList(), any(), any(), any())).willReturn("OCCUPIED");

        signalingService.joinSession(socket, joinRequest(SESSION_CODE));

        verify(sessionManager).sendMessage(eq("late-sock"), any()); // ERROR 응답
        verify(metrics).countJoin("occupied");
        verify(sessionService, never()).joinSession(any(), any(), any(), any(), any());
        verify(valueOperations, never()).set(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("JOIN: 종료된 세션(ENDED)이면 부활 없이 에러만 보낸다 — 묘비 가드 (#124)")
    void joinSession_endedSession_rejectedWithoutResurrection() {
        given(socket.getId()).willReturn("revive-sock");
        given(socket.getAttributes()).willReturn(java.util.Map.of("userId", 2L));
        given(redisTemplate.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<String>>any(),
                org.mockito.ArgumentMatchers.anyList(), any(), any(), any())).willReturn("ENDED");

        signalingService.joinSession(socket, joinRequest(SESSION_CODE));

        verify(sessionManager).sendMessage(eq("revive-sock"), any());          // SESSION_NOT_FOUND 에러 응답
        verify(metrics).countJoin("ended");                                    // 부활 시도가 지표로 관측된다
        verify(valueOperations, never()).set(any(), any(), anyLong(), any()); // 매핑 등록 없음 = 부활 없음
        verify(relaySender, never()).send(any(), any());                       // PEER_RECONNECTED 없음
    }

    @Test
    @DisplayName("JOIN: 레이트리밋 초과 시 클레임 스크립트 실행 없이 에러만 보낸다 (#125)")
    void joinSession_rateLimited_rejectedBeforeClaim() {
        given(socket.getId()).willReturn("burst-sock");
        given(socket.getAttributes()).willReturn(java.util.Map.of("userId", 2L));
        given(rateLimiter.tryAcquire(startsWith("rl:join:"), org.mockito.ArgumentMatchers.anyInt(), any()))
                .willReturn(false);

        signalingService.joinSession(socket, joinRequest(SESSION_CODE));

        verify(metrics).countJoin("rate_limited");                     // 폭주가 지표로 관측된다
        verify(sessionManager).sendMessage(eq("burst-sock"), any());   // TOO_MANY_REQUESTS 에러 응답
        verify(redisTemplate, never()).execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<String>>any(),
                org.mockito.ArgumentMatchers.anyList(), any(), any(), any());
    }

    private com.caestro.server.domain.signaling.dto.request.SignalingRequest joinRequest(String code) {
        return new com.caestro.server.domain.signaling.dto.request.SignalingRequest(
                "JOIN_SESSION", code, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    @DisplayName("[회귀] 방장 소켓 disconnect는 유예 처리(ownerSocketId=null, WAITING)하고 상대에게 알린다")
    void handleDisconnect_ownerSocket_gracePeriod() throws Exception {
        givenConnectedSession(OWNER_SOCKET);

        signalingService.handleDisconnect(socket);

        verify(relaySender).send(eq(PARTICIPANT_SOCKET), any());

        ArgumentCaptor<String> savedJson = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("session:" + SESSION_CODE), savedJson.capture(),
                anyLong(), any(TimeUnit.class));
        SessionInfo saved = objectMapper.readValue(savedJson.getValue(), SessionInfo.class);
        assertThat(saved.getOwnerSocketId()).isNull();
        assertThat(saved.getOwnerUserId()).isEqualTo(1L); // takeover 가능하도록 정체성은 유지
        assertThat(saved.getStatus()).isEqualTo("WAITING");

        verify(redisTemplate).delete("socket:" + OWNER_SOCKET);
    }

    @Test
    @DisplayName("[회귀] 참여자 소켓 disconnect는 슬롯을 비우고(WAITING) 디렉터를 방장으로 되돌린다")
    void handleDisconnect_participantSocket_clearsSlot() throws Exception {
        givenConnectedSession(PARTICIPANT_SOCKET);

        signalingService.handleDisconnect(socket);

        verify(relaySender).send(eq(OWNER_SOCKET), any());

        ArgumentCaptor<String> savedJson = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("session:" + SESSION_CODE), savedJson.capture(),
                anyLong(), any(TimeUnit.class));
        SessionInfo saved = objectMapper.readValue(savedJson.getValue(), SessionInfo.class);
        assertThat(saved.getParticipantUserId()).isNull();
        assertThat(saved.getParticipantSocketId()).isNull();
        assertThat(saved.getCurrentDirectorUserId()).isEqualTo(1L);
        assertThat(saved.getStatus()).isEqualTo("WAITING");

        verify(redisTemplate).delete("socket:" + PARTICIPANT_SOCKET);
    }
}
