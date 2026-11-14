package com.caestro.server.global.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 서킷이 열리고 닫히는 순간이 지표에 남는다(#271). 로그 한 줄은 CloudWatch 알람 peakpic-app-circuit-open 의 재료다. */
class ResilienceConfigTest {

    @Test
    @DisplayName("서킷이 OPEN 으로 가면 circuit.transition{name,to=OPEN} 이 1 오르고, CLOSED 로 돌아오면 to=CLOSED 가 오른다")
    void transitionsAreCounted() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreakerRegistry registry = new ResilienceConfig().circuitBreakerRegistry(meters);
        CircuitBreaker fcm = registry.circuitBreaker("fcm");

        fcm.transitionToOpenState();
        fcm.transitionToClosedState();

        assertThat(meters.counter("circuit.transition", "name", "fcm", "to", "OPEN").count()).isEqualTo(1.0);
        assertThat(meters.counter("circuit.transition", "name", "fcm", "to", "CLOSED").count()).isEqualTo(1.0);
    }
}
