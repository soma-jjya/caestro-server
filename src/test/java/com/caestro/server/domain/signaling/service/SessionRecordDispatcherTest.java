package com.caestro.server.domain.signaling.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 세션 기록 디스패처의 보장 검증 (#99 → #124 → #125).
 * 순서 보장 테스트는 철거됐다 — 순서 무관성은 서비스 계층의 멱등 upsert가 담당하며
 * SessionRecordOrderingTest(6순열)가 증명한다. 여기서는 큐의 역할을 검증한다:
 * 비동기 버퍼 경계(포화 드롭·예외 격리·워커 생존) / 재시도(재실행 안전한 오류만) /
 * DB 서킷(장애 지속 시 재시도 대기 없이 즉시 실패).
 */
class SessionRecordDispatcherTest {

    private SimpleMeterRegistry registry;
    private SignalingMetrics metrics;
    private CircuitBreakerRegistry circuitBreakers;
    private SessionRecordDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SignalingMetrics(registry);
        circuitBreakers = CircuitBreakerRegistry.ofDefaults(); // 서킷 설정은 디스패처가 자체 주입
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    @Test
    @DisplayName("큐 포화 시 작업을 버리고(WS 스레드 비블로킹) 드롭 지표를 남긴다")
    void saturatedQueue_dropsAndCounts() throws Exception {
        dispatcher = new SessionRecordDispatcher(metrics, circuitBreakers, 1, 1); // 워커 1 + 큐 1칸
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
    @DisplayName("영구 오류(재시도 대상 아님)는 즉시 격리된다 — 다음 작업 실행, 실패 지표 1회")
    void permanentFailure_isolatedWithoutRetry() throws Exception {
        dispatcher = new SessionRecordDispatcher(metrics, circuitBreakers, 1, 100);
        CountDownLatch done = new CountDownLatch(1);

        dispatcher.dispatch("B", "create", () -> {
            throw new RuntimeException("영구 오류 — 재시도 무의미");
        });
        dispatcher.dispatch("B", "join", done::countDown);

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue(); // 워커 생존 = 후속 작업 실행됨
        assertThat(registry.get("ws.record.failed").tag("task", "create").counter().count()).isEqualTo(1);
        assertThat(registry.find("ws.record.retried").counter()).isNull(); // 재시도 없이 즉시 실패
    }

    @Test
    @DisplayName("일시 DB 오류는 재시도로 흡수된다 — 회복하면 실패 지표 없음 (#124)")
    void transientError_retriedAndRecovers() throws Exception {
        dispatcher = new SessionRecordDispatcher(metrics, circuitBreakers, 1, 100);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        dispatcher.dispatch("C", "join", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new QueryTimeoutException("일시 오류 — 다음 시도에 회복");
            }
            done.countDown();
        });

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.get("ws.record.retried").tag("task", "join").counter().count()).isEqualTo(1);
        assertThat(registry.find("ws.record.failed").counter()).isNull();
    }

    @Test
    @DisplayName("SESSION_NOT_FOUND(create 기록이 아직 다른 인스턴스에서 오는 중)도 재시도가 구제한다")
    void sessionNotYetRecorded_retriedAndRecovers() throws Exception {
        dispatcher = new SessionRecordDispatcher(metrics, circuitBreakers, 1, 100);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        // spec 기록이 세션 행보다 먼저 도착한 상황 — 두 번째 시도 땐 행이 생겨 성공
        dispatcher.dispatch("D", "spec", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new CustomException(ErrorCode.SESSION_NOT_FOUND);
            }
            done.countDown();
        });

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.get("ws.record.retried").tag("task", "spec").counter().count()).isEqualTo(1);
        assertThat(registry.find("ws.record.failed").counter()).isNull();
    }

    @Test
    @DisplayName("재시도 소진(3회) 후에는 실패 지표를 남기고 워커는 생존한다")
    void retryExhausted_countsFailureAndSurvives() throws Exception {
        dispatcher = new SessionRecordDispatcher(metrics, circuitBreakers, 1, 100);
        CountDownLatch attempts = new CountDownLatch(3);
        CountDownLatch next = new CountDownLatch(1);

        dispatcher.dispatch("E", "end", () -> {
            attempts.countDown();
            throw new QueryTimeoutException("DB 장기 불능");
        });
        dispatcher.dispatch("E", "create", next::countDown);

        assertThat(attempts.await(3, TimeUnit.SECONDS)).isTrue(); // 정확히 3회 시도
        assertThat(next.await(3, TimeUnit.SECONDS)).isTrue();      // 워커 생존
        assertThat(registry.get("ws.record.failed").tag("task", "end").counter().count()).isEqualTo(1);
        assertThat(registry.get("ws.record.retried").tag("task", "end").counter().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("DB 인프라 장애가 누적되면 서킷이 열려, 이후 작업은 재시도 대기 없이 즉시 실패한다 (#125)")
    void dbOutage_opensCircuit_thenSkipsWithoutSleep() throws Exception {
        dispatcher = new SessionRecordDispatcher(metrics, circuitBreakers, 1, 100);
        CountDownLatch attempts = new CountDownLatch(15); // 5작업 × 3회 재시도 = 표본 5개 전부 실패

        for (int i = 0; i < 5; i++) {
            dispatcher.dispatch("F" + i, "create", () -> {
                attempts.countDown();
                throw new CannotCreateTransactionException("DB down");
            });
        }
        assertThat(attempts.await(8, TimeUnit.SECONDS)).isTrue();
        waitUntilFailedCount(5);
        assertThat(circuitBreakers.circuitBreaker("session-record-db").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        // open 동안 도착한 작업은 실행 자체가 생략된다 (재시도 sleep으로 큐가 막히지 않음)
        AtomicInteger blockedRuns = new AtomicInteger();
        dispatcher.dispatch("F9", "create", blockedRuns::incrementAndGet);
        waitUntilFailedCount(6);

        assertThat(blockedRuns.get()).isZero();
        assertThat(registry.get("ws.record.failed").tag("task", "create").counter().count()).isEqualTo(6);
    }

    private void waitUntilFailedCount(double expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var counter = registry.find("ws.record.failed").tag("task", "create").counter();
            if (counter != null && counter.count() >= expected) {
                return;
            }
            Thread.sleep(50);
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
