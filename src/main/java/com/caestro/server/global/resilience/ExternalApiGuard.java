package com.caestro.server.global.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 외부 API 호출 공용 가드 (#125): 서킷브레이커 + 멱등 호출 한정 1회 재시도.
 * 감싸는 순서는 Retry(CircuitBreaker(호출)) — 재시도된 각 호출이 서킷의 표본으로 집계되고,
 * 서킷이 열리면(CallNotPermittedException) 재시도 없이 즉시 실패한다.
 */
@Component
public class ExternalApiGuard {

    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryConfig idempotentRetryConfig;

    public ExternalApiGuard(CircuitBreakerRegistry circuitBreakers) {
        this.circuitBreakers = circuitBreakers;
        // 멱등 호출 전용: 인프라 장애만 1회 재시도. 서킷 열림·4xx는 재시도해도 결과가 같으므로 제외
        this.idempotentRetryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(200))
                .retryOnException(e -> !(e instanceof CallNotPermittedException)
                        && ResilienceConfig.isInfrastructureFailure(e))
                .build();
    }

    /**
     * 멱등 호출(사용자 정보·JWKS 조회 같은 GET류): 서킷브레이커 + 실패 시 1회 재시도.
     *
     * @param name 서킷 이름 (provider 단위 — 같은 이름은 장애 상태를 공유한다)
     */
    public <T> T idempotent(String name, Supplier<T> call) {
        CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(name);
        Retry retry = Retry.of(name + "-idempotent", idempotentRetryConfig);
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
    }

    /**
     * 비멱등 호출(인가코드 교환 등 1회성 자원 소비): 서킷브레이커만 — 재시도는 금지.
     * 인가코드는 1회용이라 재시도가 성공은커녕 invalid_grant를 만든다.
     */
    public <T> T oneShot(String name, Supplier<T> call) {
        return CircuitBreaker.decorateSupplier(circuitBreakers.circuitBreaker(name), call).get();
    }
}
