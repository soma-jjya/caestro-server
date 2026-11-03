package com.caestro.server.domain.b2b.service;

import static org.assertj.core.api.Assertions.*;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class B2bMetricsTest {
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final B2bMetrics metrics = new B2bMetrics(registry);

    @Test
    void preservesResultAndRecordsDurationIncludingCompletedAction() {
        Object result = new Object();
        assertThat(metrics.observe(B2bMetrics.Operation.CREATE, () -> {
            clock.add(Duration.ofMillis(230));
            return result;
        })).isSameAs(result);
        var timer = registry.get("b2b.operation").tags("operation", "create", "outcome", "success").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(230);
    }

    @Test
    void recordsRateLimitWithoutSwallowingOrReplacingException() {
        CustomException failure = new CustomException(ErrorCode.TOO_MANY_REQUESTS);
        assertThatThrownBy(() -> metrics.observe(B2bMetrics.Operation.CREATE, () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(registry.get("b2b.operation").tags("operation", "create", "outcome", "client_error")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void recordsUnexpectedDatabaseFailureAndKeepsBoundedTags() {
        RuntimeException failure = new IllegalStateException("sensitive detail must not become a label");
        assertThatThrownBy(() -> metrics.observe(B2bMetrics.Operation.PROJECT_LOCK_LOOKUP, () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(registry.get("b2b.operation").tags("operation", "project_lock_lookup", "outcome", "server_error")
                .timer().count()).isEqualTo(1);
        assertThat(registry.find("b2b.operation").timers()).hasSize(12).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).extracting("key").containsExactly("operation", "outcome"));
    }

    @Test
    void prometheusExportsHistogramForDashboardQueries() {
        var prometheus = new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        try {
            new B2bMetrics(prometheus).observe(B2bMetrics.Operation.ENTRY, () -> "ok");
            String scrape = prometheus.scrape();
            assertThat(scrape).contains("b2b_operation_seconds_bucket{", "b2b_operation_seconds_count{",
                    "b2b_operation_seconds_sum{", "operation=\"entry\"", "outcome=\"success\"", "le=\"0.05\"");
            assertThat(scrape).doesNotContain("slug=", "code=", "user=", "ip=");
        } finally {
            prometheus.close();
        }
    }
}
