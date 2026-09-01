package com.caestro.server.domain.signaling.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 배포·종료 시 WS 연결을 정돈해서 닫는 드레인 (#106).
 *
 * 롤링 배포는 연결을 옮겨주지 않는다 — 프로세스가 죽으면 소켓은 반드시 끊긴다. 대신 "덜 거칠게" 끊는다:
 * 클라이언트에 1012(SERVICE_RESTARTED)를 보내 즉시 재접속하라는 뜻을 전하고, 종료 시점을 jitter로
 * 흩뿌려 재접속 몰림을 막으며, Redis 등 빈이 파괴되기 전에 닫아 이탈 처리(handleDisconnect)가 정상 실행되게 한다.
 *
 * Boot의 graceful shutdown은 처리 중인 HTTP만 기다리고 WS 세션은 세지 않으므로 종료 순서에 직접 끼어든다:
 * phase를 HTTP graceful보다 높게 잡아 [WS 드레인 → HTTP graceful → 빈 파괴] 순으로 실행된다.
 * (before 측정에서 Redis가 먼저 파괴돼 handleDisconnect 40건 전부가 실패한 것이 이 순서의 근거)
 */
@Slf4j
@Component
public class SignalingDrainLifecycle implements SmartLifecycle {

    private final WebSocketSessionManager sessionManager;
    private final SignalingMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final long jitterMs;
    private final long maxWaitMs;

    private volatile boolean running = false;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    // 드레인은 한 번만 실행된다 — IMDS 감지(#111)와 SIGTERM 두 경로가 모두 부를 수 있으므로 멱등
    private final AtomicBoolean drained = new AtomicBoolean(false);

    public SignalingDrainLifecycle(WebSocketSessionManager sessionManager,
                                   SignalingMetrics metrics,
                                   ApplicationEventPublisher eventPublisher,
                                   @Value("${signaling.drain.jitter-ms:3000}") long jitterMs,
                                   @Value("${signaling.drain.max-wait-ms:10000}") long maxWaitMs) {
        this.sessionManager = sessionManager;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.jitterMs = jitterMs;
        this.maxWaitMs = maxWaitMs;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // Boot의 HTTP graceful shutdown(WebServerGracefulShutdownLifecycle)은 DEFAULT_PHASE - 1024에서 멈춘다.
    // 그보다 1 높게 잡아 먼저 멈춘다. (Boot 상수는 4.0부터 deprecated라 값을 직접 두고, 테스트가 순서를 검증한다)
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 1024 + 1;

    /** stop()은 phase가 높은 것부터 호출된다 → HTTP graceful보다 먼저 WS를 정돈한다. */
    @Override
    public int getPhase() {
        return PHASE;
    }

    /** 드레인 중이면 true — 이후 들어오는 새 연결은 등록하지 않고 1012로 돌려보낸다. */
    public boolean isDraining() {
        return draining.get();
    }

    /**
     * SIGTERM 경로. 운영에선 보통 IMDS 감지(#111)가 먼저 드레인을 끝내 놓으므로 여기선 no-op가 되고,
     * 로컬(docker stop)·하드 정지처럼 등록 해제 신호가 없는 경우의 안전망이다.
     */
    @Override
    public void stop() {
        drain("sigterm");
    }

    /**
     * 로컬 소켓 전부에 1012를 0~jitter-ms 무작위 지연으로 분산 전송하고, 전부 닫히거나 max-wait-ms까지 기다린다.
     * 한 번만 실행된다(두 번째 호출은 무시). SIGTERM 경로에선 반환 후에야 Spring이 다음 종료 단계로 넘어간다.
     *
     * @param trigger 로그용 — "sigterm" 또는 "imds:Terminated"
     */
    public void drain(String trigger) {
        if (!drained.compareAndSet(false, true)) {
            log.info("Drain already done, ignoring trigger={}", trigger);
            return;
        }
        draining.set(true);
        // readiness → 503: LB가 아직 이 인스턴스를 보고 있더라도 새 트래픽을 받지 않겠다는 신호
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);

        List<WebSocketSession> sockets = sessionManager.snapshot();
        running = false;
        if (sockets.isEmpty()) {
            log.info("Drain({}): no active websocket session", trigger);
            return;
        }

        long startedAt = System.currentTimeMillis();
        log.info("Drain start({}): closing {} session(s) with 1012, jitter={}ms, maxWait={}ms",
                trigger, sockets.size(), jitterMs, maxWaitMs);

        // close → afterConnectionClosed → handleDisconnect(Redis)까지 호출 스레드에서 동기 실행되므로 스레드 2개로 분산한다
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ws-drain");
            t.setDaemon(true);
            return t;
        });
        CountDownLatch done = new CountDownLatch(sockets.size());
        AtomicInteger closed = new AtomicInteger();
        try {
            for (WebSocketSession socket : sockets) {
                long delay = jitterMs > 0 ? ThreadLocalRandom.current().nextLong(jitterMs) : 0;
                scheduler.schedule(() -> {
                    try {
                        socket.close(CloseStatus.SERVICE_RESTARTED);
                        closed.incrementAndGet();
                    } catch (Exception e) {
                        log.warn("Drain: failed to close session {}", socket.getId(), e);
                    } finally {
                        done.countDown();
                    }
                }, delay, TimeUnit.MILLISECONDS);
            }
            boolean all = done.await(maxWaitMs, TimeUnit.MILLISECONDS);
            metrics.countDrainClosed(closed.get());
            log.info("Drain {}: closed {}/{} in {}ms", all ? "complete" : "timed out",
                    closed.get(), sockets.size(), System.currentTimeMillis() - startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Drain interrupted after closing {}/{}", closed.get(), sockets.size());
        } finally {
            scheduler.shutdownNow();
        }
    }
}
