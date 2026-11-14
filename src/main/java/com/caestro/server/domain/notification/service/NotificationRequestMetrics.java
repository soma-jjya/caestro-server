package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.fill.event.FillCompletedEvent;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.support.event.SupportRepliedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 알림 유실 계측 (#271). 발송 요청이 **생기는 순간**을 센다 — 커밋 뒤 비동기 구간(알림함 저장 → FCM)보다 앞이라,
 * {@code notification.requested} 와 {@code push.sent{result}} 를 나란히 놓으면 그 사이에서 잃은 수가 보인다.
 * <pre>잃은 수 = requested − sent − unregistered</pre>
 * 이벤트 발행 자리에서는 트랜잭션 안의 동기 리스너로 센다(비동기 리스너는 그 자체가 유실 지점이라 쓰지 않는다).
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

    /** 네 칸 완성: 주인 1 + 칸을 채운 참여자. 발행 스레드에서 바로 센다(FillCompletedPushListener 보다 앞). */
    @EventListener
    public void onFillCompleted(FillCompletedEvent event) {
        requested(PushMessage.TYPE_FILL_COMPLETED, 1 + event.participantIds().size());
    }

    /** 문의 답변: 문의한 사람 1. */
    @EventListener
    public void onSupportReplied(SupportRepliedEvent event) {
        requested(PushMessage.TYPE_SUPPORT_REPLY, 1);
    }
}
