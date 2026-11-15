package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.notification.entity.NotificationOutbox;
import com.caestro.server.domain.notification.repository.NotificationOutboxRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.service.PushService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 아웃박스 행 한 건을 실제로 보내는 부분 (#272 릴레이에서 떼어 냄, #273 소비자와 공유). 시한 확인 → (묶음 알림이면 문구 다시 만들기, #274) → 알림함 행 한 번 → FCM.
 * 끝난 경우(SENT·EXPIRED·CANCELLED)는 여기서 행을 저장하고, 다시 보낼지(RETRY)는 부르는 쪽이 정한다 — 릴레이는 not_before 로, 소비자는 큐(PEL)로.
 * 어떤 예외도 밖으로 내지 않고 RETRY 로 돌려준다(#269 교훈: 로그·부수 코드가 핵심 경로를 넘어뜨리지 않게).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDelivery {

    static final String METRIC_PROCESSED = "notification.relay.processed";

    public enum Result { SENT, EXPIRED, CANCELLED, RETRY }

    public record Outcome(Result result, String error) {
        static Outcome of(Result result) { return new Outcome(result, null); }
    }

    private final NotificationOutboxRepository repository;
    private final NotificationService notificationService;
    private final PushService pushService;
    private final NotificationRequestMetrics metrics;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final List<OutboxMessageRenderer> renderers;

    public Outcome deliver(NotificationOutbox row, LocalDateTime now) {
        long startedNanos = System.nanoTime();
        try {
            if (row.isExpired(now)) {
                row.expired(now);
                save(row);
                count(row, "expired");
                return Outcome.of(Result.EXPIRED);
            }
            PushMessage message = messageOf(row);
            // 묶음 알림: 합쳐진 뒤의 상태로 문구를 다시 만든다. 재시도에서는 알림함 행이 이미 있어 처음 만든 문구를 그대로 쓴다
            if (row.getNotificationId() == null) {
                Optional<OutboxMessageRenderer> renderer = rendererOf(row.getType());
                if (renderer.isPresent()) {
                    Optional<PushMessage> rendered = renderer.get().render(message);
                    if (rendered.isEmpty()) {
                        row.cancelled(now);
                        save(row);
                        count(row, "cancelled");
                        metrics.cancelled(row.getType(), 1);
                        return Outcome.of(Result.CANCELLED);
                    }
                    message = rendered.get();
                    row.rendered(message.title(), message.body());
                }
            }
            // 알림함 행은 한 번만 — 재시도에도 앱 알림함에는 한 줄
            if (row.getNotificationId() == null) {
                try {
                    row.inboxRecorded(notificationService.record(row.getUserId(), message).getId());
                    save(row);
                } catch (RuntimeException e) {
                    metrics.recordFailed(row.getType());
                    log.warn("Outbox inbox record failed; push goes without notificationId (id={}, type={}, cause={}: {})",
                            row.getId(), row.getType(), e.getClass().getSimpleName(), e.getMessage());
                }
            }
            PushMessage toSend = row.getNotificationId() == null ? message : message.withNotificationId(row.getNotificationId());
            PushService.SendReport report = pushService.sendReport(row.getUserId(), toSend);
            if (report.shouldRetry()) {
                return new Outcome(Result.RETRY, "push failed: tokens=" + report.tokens() + " failed=" + report.failed());
            }
            row.sent(now);
            save(row);
            count(row, "sent");
            // 도착 지연(#283): 보낼 시각부터 이 처리를 시작한 때 + 알림함·FCM 에 쓴 시간
            metrics.deliveryLatency(row.getType(), Duration.between(row.getNotBefore(), now).plusNanos(System.nanoTime() - startedNanos));
            return Outcome.of(Result.SENT);
        } catch (RuntimeException e) {
            return new Outcome(Result.RETRY, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Optional<OutboxMessageRenderer> rendererOf(String type) {
        return renderers.stream().filter(r -> r.type().equals(type)).findFirst();
    }

    void count(NotificationOutbox row, String result) {
        meterRegistry.counter(METRIC_PROCESSED, "type", row.getType(), "result", result).increment();
    }

    void save(NotificationOutbox row) {
        transaction.executeWithoutResult(s -> repository.save(row));
    }

    PushMessage messageOf(NotificationOutbox row) {
        Map<String, String> data;
        try {
            data = objectMapper.readValue(row.getData(), new TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            data = Map.of();
        }
        return new PushMessage(row.getType(), row.getTitle(), row.getBody(), data);
    }
}
