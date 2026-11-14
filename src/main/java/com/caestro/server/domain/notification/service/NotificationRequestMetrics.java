package com.caestro.server.domain.notification.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 알림 유실 계측 (#271). 발송 요청이 **생기는 순간**(아웃박스 행 생성, #272)을 센다 — 그 뒤 단계(알림함 저장 → FCM)와 나란히 놓으면
 * 어디서 잃었는지 보인다. <pre>requested = sent + unregistered + expired + dead  (dead 는 0 이 목표)</pre>
 */
@Component
@RequiredArgsConstructor
public class NotificationRequestMetrics {

    static final String METRIC_REQUESTED = "notification.requested";
    static final String METRIC_RECORD_FAILED = "notification.record_failed";

    private final MeterRegistry meterRegistry;

    /** 종류별 요청 수. [recipients] 는 받을 사람 수(한 사건이 여러 명에게 가면 그만큼). */
    public void requested(String type, int recipients) {
        if (recipients <= 0) return;
        meterRegistry.counter(METRIC_REQUESTED, "type", type).increment(recipients);
    }

    /** 알림함 저장이 실패해 푸시만 간 수. 0 이어야 정상 — 알림함과 푸시의 수가 어긋나는 원인 중 하나. */
    public void recordFailed(String type) {
        meterRegistry.counter(METRIC_RECORD_FAILED, "type", type).increment();
    }
}
