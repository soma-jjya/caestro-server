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
        // 0 명이면 늘지 않는다(카운터는 기동 때 0 으로 이미 있다, #280)
        assertThat(registry.counter("notification.requested", "type", "fill_expiring").count()).isZero();
        assertThat(registry.counter("notification.record_failed", "type", "fill_expiring").count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("[재현 #280] 기동 직후 알려진 종류·결과의 카운터가 0 으로 이미 있다 — 처음 생긴 카운터는 프로메테우스가 0 → 1 을 못 봐 increase 가 0 이 된다")
    void knownCountersExistAtZeroFromStart() {
        for (String type : new String[]{"fill_completed", "fill_expiring", "fill_progress", "support_reply"}) {
            for (String name : new String[]{"notification.requested", "notification.recorded", "notification.opened",
                    "notification.record_failed", "notification.collapsed", "notification.cancelled"}) {
                assertThat(registry.find(name).tag("type", type).counter()).as(name + " " + type).isNotNull();
            }
            for (String result : new String[]{"sent", "unregistered", "failed"}) {
                assertThat(registry.find("push.sent").tag("type", type).tag("result", result).counter()).as("push.sent " + type + " " + result).isNotNull();
            }
            for (String result : new String[]{"sent", "expired", "cancelled", "retried", "dead"}) {
                assertThat(registry.find("notification.relay.processed").tag("type", type).tag("result", result).counter())
                        .as("relay.processed " + type + " " + result).isNotNull();
                assertThat(registry.find("notification.consumed").tag("type", type).tag("result", result).counter())
                        .as("consumed " + type + " " + result).isNotNull();
            }
        }
        assertThat(registry.find("notification.requested").tag("type", "fill_progress").counter().count()).isZero();
    }
}
