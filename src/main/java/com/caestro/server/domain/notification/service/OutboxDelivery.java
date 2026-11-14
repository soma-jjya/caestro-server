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

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 아웃박스 행 한 건을 실제로 보내는 부분 (#272 릴레이에서 떼어 냄, #273 소비자와 공유). 시한 확인 → 알림함 행 한 번 → FCM.
 * 끝난 경우(SENT·EXPIRED)는 여기서 행을 저장하고, 다시 보낼지(RETRY)는 부르는 쪽이 정한다 — 릴레이는 not_before 로, 소비자는 큐(PEL)로.
 * 어떤 예외도 밖으로 내지 않고 RETRY 로 돌려준다(#269 교훈: 로그·부수 코드가 핵심 경로를 넘어뜨리지 않게).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDelivery {

    static final String METRIC_PROCESSED = "notification.relay.processed";

    public enum Result { SENT, EXPIRED, RETRY }

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

    public Outcome deliver(NotificationOutbox row, LocalDateTime now) {
        try {
            if (row.isExpired(now)) {
                row.expired(now);
                save(row);
                count(row, "expired");
                return Outcome.of(Result.EXPIRED);
            }
            PushMessage message = messageOf(row);
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
            return Outcome.of(Result.SENT);
        } catch (RuntimeException e) {
            return new Outcome(Result.RETRY, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
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
