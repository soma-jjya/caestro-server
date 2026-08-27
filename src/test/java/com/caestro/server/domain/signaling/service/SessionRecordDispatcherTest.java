package com.caestro.server.domain.signaling.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 세션 기록 디스패처(#99)의 세 가지 보장 검증:
 * 같은 키의 순서 보장 / 포화 시 드롭(무한 큐 금지) / 작업 예외 격리(워커 생존).
 */
class SessionRecordDispatcherTest {

    private SimpleMeterRegistry registry;
    private SignalingMetrics metrics;
    private SessionRecordDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SignalingMetrics(registry);
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    @Test
    @DisplayName("같은 세션코드의 작업은 제출 순서대로 실행된다 (create→join 역전 방지)")
    void sameKey_executesInSubmissionOrder() throws Exception {
        dispatcher = new SessionRecordDispatcher(metrics, 4, 100);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(3);

        // 첫 작업을 일부러 느리게 — 순서 보장이 없다면 2·3번이 추월한다
        dispatcher.dispatch("K7P2QM", "create", () -> {
            sleep(80);
            order.add(1);
            done.countDown();
        });
        dispatcher.dispatch("K7P2QM", "join", () -> {
            order.add(2);
            done.countDown();
        });
        dispatcher.dispatch("K7P2QM", "end", () -> {
            order.add(3);
            done.countDown();
        });

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("큐 포화 시 작업을 버리고(WS 스레드 비블로킹) 드롭 지표를 남긴다")
    void saturatedQueue_dropsAndCounts() throws Exception {
        dispatcher = new SessionRecordDispatcher(metrics, 1, 1); // 워커 1 + 큐 1칸
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 워커 점유 → 큐 1칸 채움 → 다음 제출은 거부되어야 한다
        dispatcher.dispatch("A", "create", () -> {
            running.countDown();
            await(release);
        });
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        dispatcher.dispatch("A", "join", () -> { });
        dispatcher.dispatch("A", "end", () -> { });

        assertThat(registry.get("ws.record.dropped").tag("task", "end").counter().count()).isEqualTo(1);
        release.countDown();
    }

    @Test
    @DisplayName("작업 예외는 격리되어 다음 작업이 정상 실행되고 실패 지표만 남는다")
    void taskFailure_isolatedAndCounted() throws Exception {
        dispatcher = new SessionRecordDispatcher(metrics, 1, 100);
        CountDownLatch done = new CountDownLatch(1);

        dispatcher.dispatch("B", "create", () -> {
            throw new RuntimeException("DB down");
        });
        dispatcher.dispatch("B", "join", done::countDown);

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue(); // 워커 생존 = 후속 작업 실행됨
        assertThat(registry.get("ws.record.failed").tag("task", "create").counter().count()).isEqualTo(1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
