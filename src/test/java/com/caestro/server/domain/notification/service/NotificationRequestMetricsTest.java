package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.fill.event.FillCompletedEvent;
import com.caestro.server.domain.support.event.SupportRepliedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 알림 유실 계측(#271): 요청은 받을 사람 수만큼, 저장 실패는 종류별로 센다. */
class NotificationRequestMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NotificationRequestMetrics metrics = new NotificationRequestMetrics(registry);

    @Test
    @DisplayName("네 칸 완성은 주인 1 + 참여자 수, 문의 답변은 1 로 requested 에 쌓인다")
    void requestedCountsRecipients() {
        metrics.onFillCompleted(new FillCompletedEvent(1L, 10L, "K7X2MQ", List.of(11L, 12L)));
        metrics.onFillCompleted(new FillCompletedEvent(2L, 20L, "A1B2C3", List.of()));
        metrics.onSupportReplied(new SupportRepliedEvent(5L, 10L, "제목"));

        assertThat(registry.counter("notification.requested", "type", "fill_completed").count()).isEqualTo(4.0);
        assertThat(registry.counter("notification.requested", "type", "support_reply").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("받을 사람이 0 이면 세지 않고, 저장 실패는 종류별 record_failed 에 쌓인다")
    void zeroRecipientsAndRecordFailed() {
        metrics.requested("fill_expiring", 0);
        metrics.recordFailed("fill_expiring");
        metrics.recordFailed("fill_expiring");

        assertThat(registry.find("notification.requested").tag("type", "fill_expiring").counter()).isNull();
        assertThat(registry.counter("notification.record_failed", "type", "fill_expiring").count()).isEqualTo(2.0);
    }
}
