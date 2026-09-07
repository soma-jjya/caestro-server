package com.caestro.server.global.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 외부 의존 격리용 서킷브레이커 공통 설정 (#125).
 * 모놀리스는 톰캣 스레드풀을 전 기능이 공유하므로, 외부 API(카카오 등)가 침묵하면 로그인만이 아니라
 * 게스트 로그인·WS 핸드셰이크까지 전염된다 — 장애 감지 시 즉시 실패(fail-fast)로 스레드를 보호하고
 * half-open으로 자동 복구를 탐지한다. 상태 전이는 Micrometer로 노출되어 Grafana에서 관측된다.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meterRegistry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)                              // 최근 10회 호출 기준으로 판정
                .minimumNumberOfCalls(5)                            // 표본 5회 미만이면 판정 유보 (콜드스타트 오판 방지)
                .failureRateThreshold(50)                           // 실패율 50% 이상이면 open
                .waitDurationInOpenState(Duration.ofSeconds(10))    // open 유지 후 half-open으로 복구 탐지
                .permittedNumberOfCallsInHalfOpenState(2)           // 탐지용 시험 호출 2회
                .recordException(ResilienceConfig::isInfrastructureFailure)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        // 이름별 서킷 상태(closed/open/half_open)·실패율을 지표로 노출
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    /**
     * 서버측 장애만 실패로 센다. 4xx(만료 토큰·잘못된 요청 등 클라이언트 원인)로 서킷이 열리면
     * 불량 요청 폭주가 정상 사용자 로그인까지 차단하는 자기 DoS가 되기 때문이다.
     */
    static boolean isInfrastructureFailure(Throwable e) {
        if (e instanceof WebClientResponseException response) {
            return response.getStatusCode().is5xxServerError();
        }
        return true; // 응답 타임아웃·연결 거부 등 나머지는 전부 인프라 장애
    }
}
