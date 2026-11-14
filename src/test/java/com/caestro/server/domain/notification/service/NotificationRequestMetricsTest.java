package com.caestro.server.domain.notification.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 알림 유실 계측(#271): 요청은 받을 사람 수만큼, 저장 실패는 종류별로 센다. 요청을 세는 자리는 아웃박스 enqueue(#272). */
class NotificationRequestMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NotificationRequestMetrics metrics = new NotificationRequestMetrics(registry);

    @Test
    @DisplayName("요청은 받을 사람 수만큼, 0 이면 세지 않고, 저장 실패는 종류별 record_failed 에 쌓인다")
    void countsRequestsAndRecordFailures() {
        metrics.requested("fill_completed", 3);
        metrics.requested("fill_completed", 1);
        metrics.requested("fill_expiring", 0);
        metrics.recordFailed("fill_expiring");
        metrics.recordFailed("fill_expiring");

        assertThat(registry.counter("notification.requested", "type", "fill_completed").count()).isEqualTo(4.0);
        assertThat(registry.find("notification.requested").tag("type", "fill_expiring").counter()).isNull();
        assertThat(registry.counter("notification.record_failed", "type", "fill_expiring").count()).isEqualTo(2.0);
    }
}
