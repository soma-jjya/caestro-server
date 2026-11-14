package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.notification.entity.NotificationOutbox;
import com.caestro.server.domain.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 알림 스트림 소비자 (#273, contracts/notifications.md "전달 — Redis Streams").
 * <pre>
 * XREADGROUP(블로킹) → outbox 행 → 알림함 한 번 → FCM
 *   성공·만료·이미 끝남 → XACK
 *   실패 → ack 하지 않음(PEL 에 남음), attempts+1 → 스윕이 백오프(1·5·30분·2·6시간) 뒤 XCLAIM 으로 다시
 *   한도 → 보류함 스트림 XADD + 행 DEAD + XACK
 * </pre>
 * 소비자 그룹이라 서버 여러 대가 메시지를 나눠 갖고, ack 전에 죽은 소비자의 메시지는 스윕이 되찾는다(최소 한 번).
 * 두 번 처리는 행 상태(SENT 면 ack 만)가 막는다. 소비자 이름은 기동마다 새로 — 예전 이름의 PEL 은 스윕이 넘겨받는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.stream.enabled", havingValue = "true")
public class NotificationStreamConsumer {

    static final String METRIC_CONSUMED = "notification.consumed";

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final NotificationOutboxRepository repository;
    private final OutboxDelivery delivery;
    private final NotificationStreamPublisher publisher;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final OutboxSettings settings;
    private final String consumerName;
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    public NotificationStreamConsumer(RedisTemplate<String, String> redisTemplate, RedisConnectionFactory connectionFactory,
                                      NotificationOutboxRepository repository, OutboxDelivery delivery,
                                      NotificationStreamPublisher publisher, MeterRegistry meterRegistry, Clock clock,
                                      OutboxSettings settings) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.repository = repository;
        this.delivery = delivery;
        this.publisher = publisher;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.settings = settings;
        this.consumerName = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Gauge.builder("notification.stream.pending", this::pendingCount)
                .description("소비자가 꺼냈지만 아직 끝내지(ack) 못한 메시지 수. 실패해 재시도를 기다리는 것 포함").register(meterRegistry);
        Gauge.builder("notification.stream.dead", () -> safeLen(settings.deadKey()))
                .description("보류함 스트림 길이(누적, 7일 정리 전). 늘면 사람이 본다").register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!settings.workerEnabled()) {
            log.info("Notification stream consumer not started (notification.worker.enabled=false)");
            return;
        }
        ensureGroup();
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options = StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofSeconds(2))
                .batchSize(10)
                .errorHandler(e -> log.warn("Notification stream read failed: {}", e.toString()))
                .build();
        container = StreamMessageListenerContainer.create(connectionFactory, options);
        container.receive(Consumer.from(settings.group(), consumerName),
                StreamOffset.create(settings.streamKey(), ReadOffset.lastConsumed()),
                this::onMessage);
        container.start();
        log.info("Notification stream consumer started: stream={} group={} consumer={}", settings.streamKey(), settings.group(), consumerName);
    }

    /** 소비와 스윕을 함께 멈춘다(종료·테스트). */
    @PreDestroy
    public void stop() {
        if (container != null) container.stop();
        container = null;
    }

    void onMessage(MapRecord<String, String, String> message) {
        process(message.getId(), message.getValue().get(NotificationStreamPublisher.FIELD_OUTBOX_ID));
    }

    /**
     * 재시도 스윕: 끝내지 못한 메시지 중 기다릴 만큼 기다린 것을 이 소비자로 가져와(XCLAIM) 다시 처리한다.
     * 기다릴 시간은 행의 attempts 로 정한다 — 한 번도 시도 안 됨(소비자가 꺼낸 뒤 죽음)이면 claim-idle(60초), 아니면 백오프.
     */
    @Scheduled(fixedDelayString = "${notification.stream.sweep-ms:30000}", initialDelayString = "${notification.stream.sweep-ms:30000}")
    public void sweep() {
        if (!settings.workerEnabled() || container == null) return;
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            log.warn("Notification stream sweep failed: {}", e.toString());
        }
    }

    public int sweepOnce() {
        PendingMessages pending = redisTemplate.opsForStream().pending(settings.streamKey(), settings.group(), Range.unbounded(), 100);
        int reclaimed = 0;
        for (PendingMessage p : pending) {
            Optional<Long> outboxId = outboxIdOf(p.getId());
            Optional<NotificationOutbox> row = outboxId.flatMap(repository::findById);
            if (row.isEmpty() || row.get().isFinished()) {
                ack(p.getId());   // 정리됐거나 이미 끝난 행: 더 볼 것 없다
                continue;
            }
            Duration wait = settings.backoffAfter(row.get().getAttempts());
            if (p.getElapsedTimeSinceLastDelivery().compareTo(wait) < 0) continue;
            List<MapRecord<String, Object, Object>> claimed =
                    redisTemplate.opsForStream().claim(settings.streamKey(), settings.group(), consumerName, wait, p.getId());
            if (claimed.isEmpty()) continue;   // 다른 서버의 스윕이 먼저 가져감
            reclaimed++;
            process(p.getId(), String.valueOf(outboxId.get()));
        }
        return reclaimed;
    }

    /** 메시지 하나. 예외는 밖으로 내지 않는다 — ack 하지 않으면 스윕이 다시 본다. */
    void process(RecordId recordId, String rawOutboxId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Long outboxId;
        try {
            outboxId = Long.valueOf(rawOutboxId);
        } catch (RuntimeException e) {
            log.warn("Notification stream: bad message {} ({}); dropped", recordId, rawOutboxId);
            ack(recordId);
            return;
        }
        try {
            Optional<NotificationOutbox> found = repository.findById(outboxId);
            if (found.isEmpty() || found.get().isFinished()) {
                ack(recordId);
                meterRegistry.counter(METRIC_CONSUMED, "type", found.map(NotificationOutbox::getType).orElse("unknown"), "result", "skipped").increment();
                return;
            }
            NotificationOutbox row = found.get();
            OutboxDelivery.Outcome outcome = delivery.deliver(row, now);
            switch (outcome.result()) {
                case SENT, EXPIRED -> {
                    ack(recordId);
                    meterRegistry.counter(METRIC_CONSUMED, "type", row.getType(), "result", outcome.result().name().toLowerCase()).increment();
                }
                case RETRY -> {
                    boolean dead = row.deliveryFailed(now, settings.maxAttempts(), outcome.error());
                    delivery.save(row);
                    if (dead) {
                        publisher.publishDead(row.getId(), row.getType(), outcome.error());
                        ack(recordId);
                        delivery.count(row, "dead");
                        meterRegistry.counter(METRIC_CONSUMED, "type", row.getType(), "result", "dead").increment();
                        log.error("Notification DEAD after {} attempts: outbox={} type={} user={} lastError={}",
                                row.getAttempts(), row.getId(), row.getType(), row.getUserId(), outcome.error());
                    } else {
                        delivery.count(row, "retried");
                        meterRegistry.counter(METRIC_CONSUMED, "type", row.getType(), "result", "retried").increment();
                        log.warn("Notification retry {}/{} in {}: outbox={} type={} cause={}", row.getAttempts(), settings.maxAttempts(),
                                settings.backoffAfter(row.getAttempts()), row.getId(), row.getType(), outcome.error());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Notification stream process failed; left pending: outbox={} cause={}", outboxId, e.toString());
        }
    }

    private void ack(RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(settings.streamKey(), settings.group(), recordId);
    }

    private Optional<Long> outboxIdOf(RecordId id) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(settings.streamKey(), Range.closed(id.getValue(), id.getValue()));
        if (records == null || records.isEmpty()) return Optional.empty();
        Object raw = records.get(0).getValue().get(NotificationStreamPublisher.FIELD_OUTBOX_ID);
        try {
            return Optional.of(Long.valueOf(String.valueOf(raw)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 그룹이 없으면 만든다(스트림도 함께, MKSTREAM). 이미 있으면 그대로. */
    void ensureGroup() {
        try {
            redisTemplate.execute((RedisCallback<String>) c -> c.streamCommands().xGroupCreate(
                    settings.streamKey().getBytes(StandardCharsets.UTF_8), settings.group(), ReadOffset.from("0"), true));
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP") && !(e.getCause() != null && String.valueOf(e.getCause().getMessage()).contains("BUSYGROUP"))) {
                throw e;
            }
        }
    }

    private double pendingCount() {
        try {
            var summary = redisTemplate.opsForStream().pending(settings.streamKey(), settings.group());
            return summary == null ? 0 : summary.getTotalPendingMessages();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private double safeLen(String key) {
        try {
            Long len = redisTemplate.opsForStream().size(key);
            return len == null ? 0 : len;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "notifier";
        }
    }

    String consumerName() {
        return consumerName;
    }
}
