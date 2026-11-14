package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.notification.entity.NotificationOutbox;
import com.caestro.server.domain.notification.entity.OutboxStatus;
import com.caestro.server.domain.notification.repository.NotificationOutboxRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.service.PushService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 아웃박스 릴레이 (#272): 보낼 차례인 행을 집어 알림함에 남기고 FCM 으로 보낸다. 인스턴스마다 돈다(ShedLock 없음) —
 * 집기는 행 단위 UPDATE 라 두 릴레이가 같은 행을 보내지 않고, 처리량은 대수만큼 난다.
 * <ul>
 *   <li>집기: 짧은 트랜잭션으로 PENDING → PROCESSING(+lease). 보내는 동안 DB 트랜잭션을 쥐지 않는다(#184).</li>
 *   <li>실패: attempts+1, not_before 를 백오프(1·5·30분·2시간·6시간) 뒤로. 한도(기본 5)면 DEAD.</li>
 *   <li>서킷 열림·FCM 5xx 는 보고서의 failed 로 와서 같은 길로 재시도된다 — 더는 버리지 않는다.</li>
 *   <li>알림함 행은 첫 시도에 한 번만 만들고 id 를 행에 적어 둔다. 저장 실패는 세고 푸시는 그대로 간다(#211 원칙).</li>
 *   <li>죽은 릴레이가 쥔 행(lease 만료)은 다음 주기에 되찾는다 → 최소 한 번 전달. 두 번은 dedup_key·SENT 상태가 막는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class OutboxRelay {

    static final String METRIC_PROCESSED = "notification.relay.processed";
    /** 재시도 간격. attempts(1부터) 번째 실패 뒤 기다리는 시간. 넘치면 마지막 값. */
    static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6));

    private final NotificationOutboxRepository repository;
    private final NotificationService notificationService;
    private final PushService pushService;
    private final NotificationRequestMetrics metrics;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final int batch;
    private final int maxAttempts;
    private final Duration lease;

    public OutboxRelay(NotificationOutboxRepository repository, NotificationService notificationService, PushService pushService,
                       NotificationRequestMetrics metrics, TransactionTemplate transaction, ObjectMapper objectMapper,
                       MeterRegistry meterRegistry, Clock clock,
                       @Value("${notification.outbox.batch:50}") int batch,
                       @Value("${notification.outbox.max-attempts:5}") int maxAttempts,
                       @Value("${notification.outbox.lease-seconds:60}") long leaseSeconds) {
        this.repository = repository;
        this.notificationService = notificationService;
        this.pushService = pushService;
        this.metrics = metrics;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.batch = batch;
        this.maxAttempts = maxAttempts;
        this.lease = Duration.ofSeconds(leaseSeconds);
        Gauge.builder("notification.outbox.pending", () -> repository.countByStatus(OutboxStatus.PENDING))
                .description("보낼 차례를 기다리는 행").register(meterRegistry);
        Gauge.builder("notification.outbox.dead", () -> repository.countByStatus(OutboxStatus.DEAD))
                .description("재시도 한도를 넘어 사람이 봐야 하는 행. 0 이어야 한다").register(meterRegistry);
        Gauge.builder("notification.outbox.oldest_age_seconds", this::oldestPendingAgeSeconds)
                .description("가장 오래 기다린 PENDING 행의 나이. 평소 몇 초, 커지면 릴레이가 멈췄거나 FCM 장애").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${notification.outbox.poll-ms:3000}", initialDelayString = "${notification.outbox.poll-ms:3000}")
    public void run() {
        try {
            runOnce(LocalDateTime.now(clock));
        } catch (RuntimeException e) {
            log.warn("Outbox relay round failed: {}", e.toString());
        }
    }

    /** 한 바퀴. 테스트와 수동 실행이 부른다. @return 처리한 행 수 */
    public int runOnce(LocalDateTime now) {
        // 0. 죽은 릴레이가 쥔 행 되찾기
        Integer released = transaction.execute(s -> repository.releaseStale(now));
        if (released != null && released > 0) log.info("Outbox released stale rows: {}", released);
        // 1. 집기
        String token = UUID.randomUUID().toString();
        List<NotificationOutbox> claimed = transaction.execute(s -> {
            List<Long> ids = repository.findDue(OutboxStatus.PENDING, now, PageRequest.of(0, batch)).stream()
                    .map(NotificationOutbox::getId).toList();
            if (ids.isEmpty()) return List.<NotificationOutbox>of();
            repository.claim(ids, token, now.plus(lease));
            return repository.findAllByClaimTokenAndStatusOrderByIdAsc(token, OutboxStatus.PROCESSING);
        });
        if (claimed == null || claimed.isEmpty()) return 0;
        // 2. 트랜잭션 밖에서 하나씩 보낸다
        for (NotificationOutbox row : claimed) {
            deliver(row, now);
        }
        return claimed.size();
    }

    private void deliver(NotificationOutbox row, LocalDateTime now) {
        try {
            if (row.isExpired(now)) {
                finish(row, r -> r.expired(now), "expired");
                return;
            }
            PushMessage message = messageOf(row);
            // 알림함 행은 한 번만
            if (row.getNotificationId() == null) {
                try {
                    Long id = notificationService.record(row.getUserId(), message).getId();
                    row.inboxRecorded(id);
                    transaction.executeWithoutResult(s -> repository.save(row));
                } catch (RuntimeException e) {
                    metrics.recordFailed(row.getType());
                    log.warn("Outbox inbox record failed; push goes without notificationId (id={}, type={}, cause={}: {})",
                            row.getId(), row.getType(), e.getClass().getSimpleName(), e.getMessage());
                }
            }
            PushMessage toSend = row.getNotificationId() == null ? message : message.withNotificationId(row.getNotificationId());
            PushService.SendReport report = pushService.sendReport(row.getUserId(), toSend);
            if (report.shouldRetry()) {
                retry(row, now, "push failed: tokens=" + report.tokens() + " failed=" + report.failed());
            } else {
                finish(row, r -> r.sent(now), "sent");
                if (report.tokens() == 0) log.debug("Outbox sent without devices (inbox only): id={} type={}", row.getId(), row.getType());
            }
        } catch (RuntimeException e) {
            retry(row, now, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void retry(NotificationOutbox row, LocalDateTime now, String error) {
        Duration wait = BACKOFF.get(Math.min(row.getAttempts(), BACKOFF.size() - 1));
        row.failed(now, now.plus(wait), maxAttempts, error);
        transaction.executeWithoutResult(s -> repository.save(row));
        String result = row.getStatus() == OutboxStatus.DEAD ? "dead" : "retried";
        meterRegistry.counter(METRIC_PROCESSED, "type", row.getType(), "result", result).increment();
        if (row.getStatus() == OutboxStatus.DEAD) {
            log.error("Outbox DEAD after {} attempts: id={} type={} user={} lastError={}", row.getAttempts(), row.getId(), row.getType(), row.getUserId(), error);
        } else {
            log.warn("Outbox retry {}/{} in {}: id={} type={} cause={}", row.getAttempts(), maxAttempts, wait, row.getId(), row.getType(), error);
        }
    }

    private void finish(NotificationOutbox row, java.util.function.Consumer<NotificationOutbox> change, String result) {
        change.accept(row);
        transaction.executeWithoutResult(s -> repository.save(row));
        meterRegistry.counter(METRIC_PROCESSED, "type", row.getType(), "result", result).increment();
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

    private double oldestPendingAgeSeconds() {
        LocalDateTime oldest = repository.oldestPendingCreatedAt();
        return oldest == null ? 0 : Math.max(0, ChronoUnit.SECONDS.between(oldest, LocalDateTime.now(clock)));
    }
}
