package com.caestro.server.global.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 외부 API 가드(#125)의 계약 검증: 멱등 호출만 인프라 장애 1회 재시도 / 4xx는 재시도·서킷 표본 제외
 * (자기 DoS 방지) / 서버 장애 누적 시 open → 이후 호출은 시도 자체가 없다(스레드 보호).
 */
class ExternalApiGuardTest {

    private CircuitBreakerRegistry registry;
    private ExternalApiGuard guard;

    @BeforeEach
    void setUp() {
        // 테스트용 소형 창 — 표본 2개부터 판정 (운영 설정과 임계 원리는 동일)
        registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .recordException(ResilienceConfig::isInfrastructureFailure)
                .build());
        guard = new ExternalApiGuard(registry);
    }

    @Test
    @DisplayName("멱등 호출: 인프라 장애(타임아웃·절단류)면 1회 재시도해 회복한다")
    void idempotent_retriesOnceOnInfrastructureFailure() {
        AtomicInteger calls = new AtomicInteger();

        String result = guard.idempotent("ext-a", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("connection reset");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("비멱등 호출(인가코드 교환): 실패해도 재시도하지 않는다 — 1회성 코드는 재시도가 invalid_grant를 만든다")
    void oneShot_neverRetries() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard.oneShot("ext-b", () -> {
            calls.incrementAndGet();
            throw new RuntimeException("timeout");
        })).isInstanceOf(RuntimeException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("4xx(클라이언트 원인)는 재시도하지 않는다 — 다시 보내도 결과가 같다")
    void idempotent_doesNotRetryClientErrors() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard.idempotent("ext-c", () -> {
            calls.incrementAndGet();
            throw clientError(401);
        })).isInstanceOf(WebClientResponseException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("5xx가 누적되면 서킷이 열리고, 이후 호출은 시도 없이 즉시 실패한다")
    void serverErrors_openCircuit_thenFailFast() {
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            try {
                guard.oneShot("ext-d", () -> {
                    calls.incrementAndGet();
                    throw serverError();
                });
            } catch (WebClientResponseException ignored) {
            }
        }
        assertThat(registry.circuitBreaker("ext-d").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> guard.oneShot("ext-d", () -> {
            calls.incrementAndGet();
            return "unreachable";
        })).isInstanceOf(CallNotPermittedException.class);
        assertThat(calls.get()).isEqualTo(2); // open 이후엔 외부 호출 자체가 없다
    }

    @Test
    @DisplayName("4xx 폭주로는 서킷이 열리지 않는다 — 불량 토큰 폭주가 정상 로그인을 차단하는 자기 DoS 방지")
    void clientErrors_doNotOpenCircuit() {
        for (int i = 0; i < 6; i++) {
            try {
                guard.oneShot("ext-e", () -> {
                    throw clientError(400);
                });
            } catch (WebClientResponseException ignored) {
            }
        }
        assertThat(registry.circuitBreaker("ext-e").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private static WebClientResponseException clientError(int status) {
        return WebClientResponseException.create(status, "client error", new HttpHeaders(), new byte[0],
                StandardCharsets.UTF_8);
    }

    private static WebClientResponseException serverError() {
        return WebClientResponseException.create(502, "bad gateway", new HttpHeaders(), new byte[0],
                StandardCharsets.UTF_8);
    }
}
