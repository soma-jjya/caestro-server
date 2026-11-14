package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.notification.entity.NotificationOutbox;
import com.caestro.server.domain.notification.entity.OutboxStatus;
import com.caestro.server.domain.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 아웃박스 릴레이 (#272): 보낼 차례인 행을 집는다. 인스턴스마다 돈다(ShedLock 없음) — 집기는 행 단위 UPDATE 라 한 행은 한 릴레이만 갖는다.
 * <ul>
 *   <li><b>스트림 모드(#273, 기본)</b>: 집은 행을 Redis 스트림에 넣고(XADD) RELAYED 로 표시한다. 보내기·재시도·보류함은 {@link NotificationStreamConsumer}.
 *       XADD 가 실패하면(Redis 장애) 행을 PENDING 으로 되돌려 다음 바퀴에 다시 넣는다. 큐에서 사라진 RELAYED 는 lease(12시간) 뒤 되찾는다.</li>
 *   <li><b>직접 모드(#272, notification.stream.enabled=false)</b>: 릴레이가 바로 보내고, 실패하면 not_before 를 백오프만큼 미뤄 다음 바퀴에.</li>
 * </ul>
 */
@Slf4j
@Component
public class OutboxRelay {

    private final NotificationOutboxRepository repository;
    private final OutboxDelivery delivery;
    private final NotificationStreamPublisher publisher;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final OutboxSettings settings;

    @Autowired
    public OutboxRelay(NotificationOutboxRepository repository, OutboxDelivery delivery, ObjectProvider<NotificationStreamPublisher> publisher,
                       TransactionTemplate transaction, MeterRegistry meterRegistry, Clock clock, OutboxSettings settings) {
        this(repository, delivery, settings.streamEnabled() ? publisher.getIfAvailable() : null, transaction, meterRegistry, clock, settings);
    }

    OutboxRelay(NotificationOutboxRepository repository, OutboxDelivery delivery, NotificationStreamPublisher publisher,
                TransactionTemplate transaction, MeterRegistry meterRegistry, Clock clock, OutboxSettings settings) {
        this.repository = repository;
        this.delivery = delivery;
        this.publisher = publisher;
        this.transaction = transaction;
        this.clock = clock;
        this.settings = settings;
        Gauge.builder("notification.outbox.pending", () -> repository.countByStatus(OutboxStatus.PENDING))
                .description("보낼 차례를 기다리는 행").register(meterRegistry);
        Gauge.builder("notification.outbox.dead", () -> repository.countByStatus(OutboxStatus.DEAD))
                .description("재시도 한도를 넘어 사람이 봐야 하는 행. 0 이어야 한다").register(meterRegistry);
        Gauge.builder("notification.outbox.oldest_age_seconds", this::oldestPendingAgeSeconds)
                .description("가장 오래 기다린 PENDING 행의 나이. 평소 몇 초, 커지면 릴레이가 멈췄거나 Redis 장애").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${notification.outbox.poll-ms:3000}", initialDelayString = "${notification.outbox.poll-ms:3000}")
    public void run() {
        if (!settings.workerEnabled()) return;
        try {
            runOnce(LocalDateTime.now(clock));
        } catch (RuntimeException e) {
            log.warn("Outbox relay round failed: {}", e.toString());
        }
    }

    /** 한 바퀴. 테스트와 수동 실행이 부른다. @return 집은 행 수 */
    public int runOnce(LocalDateTime now) {
        // 0. 죽은 릴레이가 쥔 행, 큐에서 사라진 행 되찾기
        Integer released = transaction.execute(s -> repository.releaseStale(now));
        if (released != null && released > 0) log.info("Outbox released stale rows: {}", released);
        // 1. 집기
        String token = UUID.randomUUID().toString();
        List<NotificationOutbox> claimed = transaction.execute(s -> {
            List<Long> ids = repository.findDue(OutboxStatus.PENDING, now, PageRequest.of(0, settings.batch())).stream()
                    .map(NotificationOutbox::getId).toList();
            if (ids.isEmpty()) return List.<NotificationOutbox>of();
            repository.claim(ids, token, now.plus(settings.lease()));
            return repository.findAllByClaimTokenAndStatusOrderByIdAsc(token, OutboxStatus.PROCESSING);
        });
        if (claimed == null || claimed.isEmpty()) return 0;
        // 2. 트랜잭션 밖에서 하나씩
        if (publisher != null) {
            relayToStream(claimed, now);
        } else {
            for (NotificationOutbox row : claimed) deliverDirectly(row, now);
        }
        return claimed.size();
    }

    private void relayToStream(List<NotificationOutbox> claimed, LocalDateTime now) {
        for (NotificationOutbox row : claimed) {
            try {
                publisher.publish(row.getId());
                row.relayed(now.plus(settings.relayedLease()));
                delivery.save(row);
            } catch (RuntimeException e) {
                // Redis 가 안 되면 행을 그대로 두고(PROCESSING, lease 60초) 다음 바퀴에 다시 — 요청은 DB 에 있다
                log.warn("Outbox relay to stream failed; will retry: id={} cause={}", row.getId(), e.toString());
            }
        }
        try {
            publisher.trim();
        } catch (RuntimeException e) {
            log.debug("Stream trim failed: {}", e.toString());
        }
    }

    private void deliverDirectly(NotificationOutbox row, LocalDateTime now) {
        OutboxDelivery.Outcome outcome = delivery.deliver(row, now);
        if (outcome.result() != OutboxDelivery.Result.RETRY) return;
        Duration wait = settings.backoffAfter(row.getAttempts() + 1);
        row.failed(now, now.plus(wait), settings.maxAttempts(), outcome.error());
        delivery.save(row);
        boolean dead = row.getStatus() == OutboxStatus.DEAD;
        delivery.count(row, dead ? "dead" : "retried");
        if (dead) {
            log.error("Outbox DEAD after {} attempts: id={} type={} user={} lastError={}", row.getAttempts(), row.getId(), row.getType(), row.getUserId(), outcome.error());
        } else {
            log.warn("Outbox retry {}/{} in {}: id={} type={} cause={}", row.getAttempts(), settings.maxAttempts(), wait, row.getId(), row.getType(), outcome.error());
        }
    }

    private double oldestPendingAgeSeconds() {
        LocalDateTime oldest = repository.oldestPendingCreatedAt();
        return oldest == null ? 0 : Math.max(0, ChronoUnit.SECONDS.between(oldest, LocalDateTime.now(clock)));
    }
}
