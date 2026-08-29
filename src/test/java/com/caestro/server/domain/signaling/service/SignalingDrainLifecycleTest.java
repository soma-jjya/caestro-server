package com.caestro.server.domain.signaling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 드레인(#106)의 보장 검증: 전 소켓 1012 종료 / jitter 창 안 분산 / 대기 상한 / 개별 실패 격리 / 종료 순서(phase).
 */
@ExtendWith(MockitoExtension.class)
class SignalingDrainLifecycleTest {

    @Mock
    private WebSocketSessionManager sessionManager;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private SignalingDrainLifecycle drain(long jitterMs, long maxWaitMs) {
        SignalingDrainLifecycle lifecycle = new SignalingDrainLifecycle(
                sessionManager, new SignalingMetrics(registry), eventPublisher, jitterMs, maxWaitMs);
        lifecycle.start();
        return lifecycle;
    }

    private List<WebSocketSession> sessions(int n) {
        List<WebSocketSession> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            WebSocketSession s = mock(WebSocketSession.class);
            list.add(s);
        }
        return list;
    }

    @Test
    @DisplayName("모든 소켓이 1012(SERVICE_RESTARTED)로 닫히고, 종료 시각이 jitter 창 안에 흩어진다")
    void stop_closesAllWith1012_spreadWithinJitter() throws Exception {
        List<WebSocketSession> sockets = sessions(30);
        List<Long> closeAt = Collections.synchronizedList(new ArrayList<>());
        for (WebSocketSession s : sockets) {
            doAnswer(inv -> { closeAt.add(System.currentTimeMillis()); return null; })
                    .when(s).close(any(CloseStatus.class));
        }
        when(sessionManager.snapshot()).thenReturn(sockets);
        long jitter = 500;

        long started = System.currentTimeMillis();
        drain(jitter, 5000).stop();

        for (WebSocketSession s : sockets) {
            verify(s).close(CloseStatus.SERVICE_RESTARTED);
        }
        assertThat(closeAt).hasSize(30);
        long first = Collections.min(closeAt), last = Collections.max(closeAt);
        assertThat(last - started).isLessThan(jitter + 300);      // 창 밖으로 새지 않음
        assertThat(last - first).isGreaterThan(0);                // 한 순간에 몰리지 않음 (before: 폭 1ms)
        assertThat(registry.get("ws.drain.closed").counter().count()).isEqualTo(30);
    }

    @Test
    @DisplayName("닫기가 오래 걸려도 max-wait에서 반환한다 (종료가 무한정 막히지 않음)")
    void stop_returnsAtMaxWait() throws Exception {
        WebSocketSession slow = mock(WebSocketSession.class);
        doAnswer(inv -> { Thread.sleep(3000); return null; }).when(slow).close(any(CloseStatus.class));
        when(sessionManager.snapshot()).thenReturn(List.of(slow));

        long started = System.currentTimeMillis();
        drain(0, 300).stop();

        assertThat(System.currentTimeMillis() - started).isLessThan(1500);
    }

    @Test
    @DisplayName("한 소켓의 close 실패는 나머지에 영향을 주지 않고, 성공 수만 지표에 남는다")
    void stop_isolatesIndividualFailure() throws Exception {
        List<WebSocketSession> sockets = sessions(3);
        doThrow(new IOException("already closed")).when(sockets.get(1)).close(any(CloseStatus.class));
        when(sessionManager.snapshot()).thenReturn(sockets);

        drain(0, 2000).stop();

        verify(sockets.get(0)).close(CloseStatus.SERVICE_RESTARTED);
        verify(sockets.get(2)).close(CloseStatus.SERVICE_RESTARTED);
        assertThat(registry.get("ws.drain.closed").counter().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("stop 이후 draining=true — 이때 들어온 새 연결은 핸들러가 1012로 돌려보낸다")
    void stop_marksDraining() {
        when(sessionManager.snapshot()).thenReturn(List.of());
        SignalingDrainLifecycle lifecycle = drain(0, 100);
        assertThat(lifecycle.isDraining()).isFalse();

        lifecycle.stop();

        assertThat(lifecycle.isDraining()).isTrue();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    @DisplayName("phase가 HTTP graceful보다 높다 → WS 드레인이 먼저, 빈(Redis) 파괴는 그 뒤")
    void phase_isBeforeHttpGracefulShutdown() {
        // Boot 내부 상수 대신 실제 라이프사이클 객체의 phase와 비교 — Boot가 값을 바꾸면 여기서 잡힌다
        int bootGracefulPhase = new WebServerGracefulShutdownLifecycle(mock(WebServer.class)).getPhase();

        assertThat(drain(0, 100).getPhase()).isGreaterThan(bootGracefulPhase);
    }
}
