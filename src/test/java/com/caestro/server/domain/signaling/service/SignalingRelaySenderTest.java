package com.caestro.server.domain.signaling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

@ExtendWith(MockitoExtension.class)
class SignalingRelaySenderTest {

    @Mock
    private WebSocketSessionManager sessionManager;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private SignalingMetrics metrics;

    private SignalingRelaySender relaySender;

    private final Object payload = Map.of("type", "PEER_JOINED");

    @BeforeEach
    void setUp() {
        // ObjectMapper는 실제 인스턴스를 사용해 발행 봉투 직렬화까지 검증한다
        relaySender = new SignalingRelaySender(sessionManager, redisTemplate, new ObjectMapper(), metrics);
    }

    @Test
    @DisplayName("대상 소켓이 로컬에 있으면 직접 전달하고 Redis로 발행하지 않는다")
    void send_localSocket_deliversDirectly() {
        given(sessionManager.isConnected("sock-local")).willReturn(true);

        relaySender.send("sock-local", payload);

        verify(sessionManager).sendMessage("sock-local", payload);
        verify(redisTemplate, never()).convertAndSend(any(), any());
        verify(metrics).countRelay(true);
    }

    @Test
    @DisplayName("대상 소켓이 로컬에 없으면 직접 전달하지 않고 Redis 채널로 발행한다")
    void send_remoteSocket_publishesToRedis() {
        given(sessionManager.isConnected("sock-remote")).willReturn(false);

        relaySender.send("sock-remote", payload);

        verify(sessionManager, never()).sendMessage(any(), any());

        // 발행 채널과 봉투(대상 소켓ID + payload)가 올바른지 검증
        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).convertAndSend(eq(SignalingRelaySender.CHANNEL), envelopeCaptor.capture());
        String envelope = (String) envelopeCaptor.getValue();
        assertThat(envelope).contains("sock-remote");
        assertThat(envelope).contains("PEER_JOINED");
        verify(metrics).countRelay(false);
    }

    @Test
    @DisplayName("발행했지만 수신 인스턴스가 0이면 유실 지표를 남긴다")
    void send_publishWithZeroReceivers_countsNoReceiver() {
        given(sessionManager.isConnected("sock-remote")).willReturn(false);
        given(redisTemplate.convertAndSend(any(), any())).willReturn(0L);

        relaySender.send("sock-remote", payload);

        verify(metrics).countRelayNoReceiver();
    }

    @Test
    @DisplayName("socketId가 null이면 로컬 전달도 발행도 하지 않는다")
    void send_nullSocketId_doesNothing() {
        relaySender.send(null, payload);

        verify(sessionManager, never()).isConnected(any());
        verify(sessionManager, never()).sendMessage(any(), any());
        verify(redisTemplate, never()).convertAndSend(any(), any());
    }
}
