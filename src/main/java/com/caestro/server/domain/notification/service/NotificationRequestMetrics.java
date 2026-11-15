package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.sender.PushResult;
import com.caestro.server.domain.push.service.PushService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 알림 유실 계측 (#271). 발송 요청이 **생기는 순간**(아웃박스 행 생성, #272)을 센다 — 그 뒤 단계(알림함 저장 → FCM)와 나란히 놓으면
 * 어디서 잃었는지 보인다. <pre>requested = relay.processed{sent} + {expired} + cancelled + 대기 중 + dead  (dead 는 0 이 목표)</pre>
 */
@Component
public class NotificationRequestMetrics {

    /** 미리 0 으로 등록할 알림 종류(#280). 종류를 더하면 여기에도. test 는 운영 지표가 아니라 뺀다. */
    static final List<String> TYPES = List.of(PushMessage.TYPE_FILL_COMPLETED, PushMessage.TYPE_FILL_EXPIRING,
            PushMessage.TYPE_FILL_PROGRESS, PushMessage.TYPE_SUPPORT_REPLY);
    static final List<String> PROCESSED_RESULTS = List.of("sent", "expired", "cancelled", "retried", "dead");

    static final String METRIC_REQUESTED = "notification.requested";
    static final String METRIC_RECORD_FAILED = "notification.record_failed";
    static final String METRIC_COLLAPSED = "notification.collapsed";
    static final String METRIC_CANCELLED = "notification.cancelled";

    private final MeterRegistry meterRegistry;

    public NotificationRequestMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerZeros();
    }

    /**
     * 알려진 종류 × 결과의 카운터를 기동 때 0 으로 만들어 둔다(#280). 처음 쓰일 때 생기는 카운터는 프로메테우스가 처음 긁을 때 이미 1 이라
     * 0 → 1 을 못 보고 {@code increase()} 가 0 이 된다 — 배포로 인스턴스가 바뀔 때마다 종류별 첫 건이 대시보드에서 사라졌다.
     */
    private void registerZeros() {
        for (String type : TYPES) {
            for (String name : List.of(METRIC_REQUESTED, METRIC_RECORD_FAILED, METRIC_COLLAPSED, METRIC_CANCELLED,
                    NotificationService.METRIC_RECORDED, NotificationService.METRIC_OPENED)) {
                meterRegistry.counter(name, "type", type);
            }
            for (PushResult result : PushResult.values()) {
                meterRegistry.counter(PushService.METRIC, "type", type, "result", result.name().toLowerCase());
            }
            for (String result : PROCESSED_RESULTS) {
                meterRegistry.counter(OutboxDelivery.METRIC_PROCESSED, "type", type, "result", result);
                meterRegistry.counter(NotificationStreamConsumer.METRIC_CONSUMED, "type", type, "result", result);
            }
            meterRegistry.counter(NotificationStreamConsumer.METRIC_CONSUMED, "type", type, "result", "skipped");
        }
    }

    /** 종류별 요청 수. [recipients] 는 받을 사람 수(한 사건이 여러 명에게 가면 그만큼). */
    public void requested(String type, int recipients) {
        if (recipients <= 0) return;
        meterRegistry.counter(METRIC_REQUESTED, "type", type).increment(recipients);
    }

    /** 이미 대기 중인 묶음 행에 합쳐진 사건 수(#274). 요청 수에는 들지 않는다 — 합치면 알림은 늘지 않는다. "채움 n번 → 알림 1통"의 n. */
    public void collapsed(String type) {
        meterRegistry.counter(METRIC_COLLAPSED, "type", type).increment();
    }

    /** 요청됐지만 보내기 전에 쓸모없어져 취소한 수(#274). 잃은 수 = 요청 − 보냄 − 만료 − 취소. */
    public void cancelled(String type, int count) {
        if (count <= 0) return;
        meterRegistry.counter(METRIC_CANCELLED, "type", type).increment(count);
    }

    /** 알림함 저장이 실패해 푸시만 간 수. 0 이어야 정상 — 알림함과 푸시의 수가 어긋나는 원인 중 하나. */
    public void recordFailed(String type) {
        meterRegistry.counter(METRIC_RECORD_FAILED, "type", type).increment();
    }
}
