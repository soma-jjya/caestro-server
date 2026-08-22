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
                sessionService, deviceSpecService, diagnosticLogger, relaySender, metrics);
        // 일부 경로(OCCUPIED 등)는 opsForValue를 쓰지 않으므로 lenient로 스텁
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /** 두 슬롯이 모두 찬 CONNECTED 세션을 Redis 모킹에 심는다. */
    private void givenConnectedSession(String disconnectingSocketId) throws Exception {
        SessionInfo info = new SessionInfo();
        info.setSessionCode(SESSION_CODE);
        info.setOwnerUserId(1L);
        info.setOwnerSocketId(OWNER_SOCKET);
        info.setParticipantUserId(2L);
        info.setParticipantSocketId(PARTICIPANT_SOCKET);
        info.setCurrentDirectorUserId(1L);
        info.setStatus("CONNECTED");

        given(socket.getId()).willReturn(disconnectingSocketId);
        given(valueOperations.get("socket:" + disconnectingSocketId)).willReturn(SESSION_CODE);
        given(valueOperations.get("session:" + SESSION_CODE))
                .willReturn(objectMapper.writeValueAsString(info));
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
        verify(sessionService).joinSession(SESSION_CODE, 2L, "APP");
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
        verify(sessionService, never()).joinSession(any(), any(), any());
        verify(valueOperations, never()).set(any(), any(), anyLong(), any());
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
