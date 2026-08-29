package com.caestro.server.domain.signaling.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.signaling.service.SignalingDrainLifecycle;
import com.caestro.server.domain.signaling.service.SignalingMetrics;
import com.caestro.server.domain.signaling.service.SignalingService;
import com.caestro.server.domain.signaling.service.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** 드레인 중 신규 연결 거절(#106) — 곧 내려갈 인스턴스에 새 세션을 붙이지 않는다. */
@ExtendWith(MockitoExtension.class)
class SignalingWebSocketHandlerTest {

    @Mock private SignalingService signalingService;
    @Mock private WebSocketSessionManager sessionManager;
    @Mock private SignalingMetrics metrics;
    @Mock private SignalingDrainLifecycle drainLifecycle;
    @Mock private WebSocketSession session;

    private SignalingWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SignalingWebSocketHandler(
                signalingService, sessionManager, new ObjectMapper(), metrics, drainLifecycle);
        given(session.getId()).willReturn("sock-1");
    }

    @Test
    @DisplayName("드레인 중 새 연결은 등록하지 않고 즉시 1012로 닫는다")
    void afterConnectionEstablished_duringDrain_rejectsWith1012() throws Exception {
        given(drainLifecycle.isDraining()).willReturn(true);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.SERVICE_RESTARTED);
        verify(sessionManager, never()).addSession(any());
    }

    @Test
    @DisplayName("드레인이 1012로 닫은 소켓은 이탈 처리 대신 매핑 정리(handleDrainDisconnect)로 간다")
    void afterConnectionClosed_drainClose_keepsSlot() {
        given(drainLifecycle.isDraining()).willReturn(true);

        handler.afterConnectionClosed(session, CloseStatus.SERVICE_RESTARTED);

        verify(signalingService).handleDrainDisconnect(session);
        verify(signalingService, never()).handleDisconnect(any());
        verify(metrics).countClose(1012);
    }

    @Test
    @DisplayName("드레인 중이라도 1012가 아닌 종료(클라 이탈)는 일반 이탈 처리로 간다")
    void afterConnectionClosed_clientCloseDuringDrain_normalPath() {
        given(drainLifecycle.isDraining()).willReturn(true);

        handler.afterConnectionClosed(session, CloseStatus.NO_CLOSE_FRAME);

        verify(signalingService).handleDisconnect(session);
        verify(signalingService, never()).handleDrainDisconnect(any());
    }

    @Test
    @DisplayName("평상시 종료는 일반 이탈 처리로 간다")
    void afterConnectionClosed_normal_regularPath() {
        given(drainLifecycle.isDraining()).willReturn(false);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(signalingService).handleDisconnect(session);
        verify(signalingService, never()).handleDrainDisconnect(any());
    }

    @Test
    @DisplayName("평상시 새 연결은 그대로 등록된다")
    void afterConnectionEstablished_normal_registers() throws Exception {
        given(drainLifecycle.isDraining()).willReturn(false);

        handler.afterConnectionEstablished(session);

        verify(sessionManager).addSession(session);
        verify(session, never()).close(any(CloseStatus.class));
    }
}
