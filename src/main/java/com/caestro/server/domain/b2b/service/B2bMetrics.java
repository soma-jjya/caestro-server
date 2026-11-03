package com.caestro.server.domain.b2b.service;

import com.caestro.server.global.exception.CustomException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** B2B 병목 관측. 사용자/slug/code/IP를 태그로 사용하지 않고 고정된 12개 타이머만 등록한다. */
@Component
public class B2bMetrics {
    public enum Operation { ENTRY, CREATE, READ, PROJECT_LOCK_LOOKUP }
    private enum Outcome { SUCCESS, CLIENT_ERROR, SERVER_ERROR }

    private final MeterRegistry registry;
    private final Map<Operation, Map<Outcome, Timer>> timers = new EnumMap<>(Operation.class);

    public B2bMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Operation operation : Operation.values()) {
            Map<Outcome, Timer> outcomes = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                outcomes.put(outcome, Timer.builder("b2b.operation")
                        .description("B2B controller service calls; lock lookup includes SQL and connection wait")
                        .tag("operation", operation.name().toLowerCase(java.util.Locale.ROOT))
                        .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                        .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(100),
                                Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
                                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10))
                        .register(registry));
            }
            timers.put(operation, outcomes);
        }
    }

    /**
     * 작업의 결과와 소요 시간을 기록하며 반환값/예외를 그대로 전달한다.
     * 컨트롤러에서 호출하면 서비스 트랜잭션 종료까지 포함한다. 인증/역직렬화/응답 전송은 포함하지 않는다.
     * @param operation 고정된 작업 종류
     * @param action 관측할 작업
     * @return 작업의 반환값
     * @throws RuntimeException 작업에서 발생한 원래 예외
     */
    public <T> T observe(Operation operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(registry);
        Outcome outcome = Outcome.SERVER_ERROR;
        try {
            T result = action.get();
            outcome = Outcome.SUCCESS;
            return result;
        } catch (CustomException exception) {
            outcome = exception.getErrorCode().getStatus() < 500 ? Outcome.CLIENT_ERROR : Outcome.SERVER_ERROR;
            throw exception;
        } finally {
            sample.stop(timers.get(operation).get(outcome));
        }
    }
}
