package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.fill.event.FillCompletedEvent;
import com.caestro.server.domain.notification.entity.NotificationOutbox;
import com.caestro.server.domain.notification.entity.OutboxStatus;
import com.caestro.server.domain.notification.repository.NotificationOutboxRepository;
import com.caestro.server.domain.notification.repository.NotificationRepository;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.service.PushService;
import com.caestro.server.domain.support.event.SupportRepliedEvent;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 아웃박스(#272)를 실제 쿼리로: 같은 트랜잭션 보장, 중복 방지, 릴레이의 보냄·재시도·만료·보류함, lease 되찾기, 동시 릴레이, 정리 (H2).
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"}, showSql = false)
@ContextConfiguration(classes = NotificationOutboxTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationOutboxTest {

    static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    static final LocalDateTime NOW_LDT = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    static final PushService.SendReport OK = new PushService.SendReport(1, 1, 0, 0);
    static final PushService.SendReport FAIL = new PushService.SendReport(1, 0, 0, 1);

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EntityScan(basePackageClasses = {NotificationOutbox.class, User.class})
    @EnableJpaRepositories(basePackageClasses = {NotificationOutboxRepository.class, UserRepository.class})
    @Import({NotificationOutboxService.class, NotificationService.class, NotificationRequestMetrics.class, OutboxEnqueuer.class})
    static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager tm) { return new TransactionTemplate(tm); }
    }

    @Autowired NotificationOutboxService outbox;
    @Autowired OutboxEnqueuer enqueuer;
    @Autowired NotificationOutboxRepository rows;
    @Autowired NotificationRepository inbox;
    @Autowired NotificationService notificationService;
    @Autowired NotificationRequestMetrics metrics;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;
    @Autowired MeterRegistry meters;
    @Autowired Clock clock;
    @MockitoBean PushService pushService;
    @MockitoBean PushTokenRepository tokens;

    OutboxRelay relay;
    Long me;

    @BeforeEach
    void setUp() {
        rows.deleteAll();
        inbox.deleteAll();
        // 지표는 컨텍스트에 누적되므로 테스트마다 비운다(릴레이 게이지는 아래서 다시 등록된다)
        ((SimpleMeterRegistry) meters).clear();
        me = users.save(User.builder().deviceId("me-" + System.nanoTime()).role(User.Role.USER).build()).getId();
        // 한도 2회로 짧게 — DEAD 를 빨리 본다
        OutboxSettings settings = new OutboxSettings(false, false, 50, 2, Duration.ofSeconds(60),
                List.of(Duration.ofMinutes(1), Duration.ofMinutes(5)), "notifications", "notifications.dead", "notifier",
                10000, Duration.ofSeconds(60), Duration.ofHours(12));
        OutboxDelivery delivery = new OutboxDelivery(rows, notificationService, pushService, metrics, tx, objectMapper, meters);
        relay = new OutboxRelay(rows, delivery, (NotificationStreamPublisher) null, tx, meters, clock, settings);
    }

    @Test
    @DisplayName("같은 트랜잭션: 사건이 롤백되면 아웃박스 행도 없고, 커밋되면 PENDING 으로 남으며 requested 가 1 오른다")
    void enqueueJoinsTheCallersTransaction() {
        tx.executeWithoutResult(s -> {
            outbox.enqueue(me, PushMessage.fillCompleted("K7X2MQ"), EnqueueOptions.dedup("rollback:1"));
            s.setRollbackOnly();
        });
        assertThat(rows.count()).isZero();

        boolean created = tx.execute(s -> outbox.enqueue(me, PushMessage.fillCompleted("K7X2MQ"), EnqueueOptions.dedup("commit:1")));
        assertThat(created).isTrue();
        NotificationOutbox row = rows.findByDedupKey("commit:1").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getNotBefore()).isEqualTo(NOW_LDT);
        assertThat(row.getData()).contains("K7X2MQ");
        assertThat(meters.counter("notification.requested", "type", "fill_completed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("중복 방지: 같은 dedupKey 는 두 번째부터 넣지 않고 false, requested 도 안 오른다")
    void duplicateKeyIsSkipped() {
        assertThat(outbox.enqueue(me, PushMessage.supportReply(5, "t"), EnqueueOptions.dedup("support_reply:5:2"))).isTrue();
        assertThat(outbox.enqueue(me, PushMessage.supportReply(5, "t"), EnqueueOptions.dedup("support_reply:5:2"))).isFalse();
        assertThat(rows.count()).isEqualTo(1);
        assertThat(meters.counter("notification.requested", "type", "support_reply").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("릴레이: 알림함 행을 만들고 그 id 를 실어 FCM 으로, 수락되면 SENT")
    void relaySendsOnce() {
        given(pushService.sendReport(eq(me), any())).willReturn(OK);
        outbox.enqueue(me, PushMessage.fillCompleted("K7X2MQ"), EnqueueOptions.dedup("fc:1"));

        assertThat(relay.runOnce(NOW_LDT)).isEqualTo(1);

        NotificationOutbox row = rows.findByDedupKey("fc:1").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getSentAt()).isEqualTo(NOW_LDT);
        assertThat(row.getNotificationId()).isNotNull();
        assertThat(inbox.count()).isEqualTo(1);
        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushService).sendReport(eq(me), sent.capture());
        assertThat(sent.getValue().data()).containsEntry("code", "K7X2MQ")
                .containsEntry(PushMessage.DATA_NOTIFICATION_ID, String.valueOf(row.getNotificationId()));
        assertThat(meters.counter("notification.relay.processed", "type", "fill_completed", "result", "sent").count()).isEqualTo(1.0);
        // 다시 돌아도 보낼 게 없다
        assertThat(relay.runOnce(NOW_LDT.plusMinutes(1))).isZero();
    }

    @Test
    @DisplayName("재시도: FCM 이 전부 실패하면 1분 뒤로 미루고, 때가 되면 다시 보내며, 알림함 행은 한 번만 만든다")
    void failedSendIsRetriedWithBackoff() {
        given(pushService.sendReport(eq(me), any())).willReturn(FAIL, OK);
        outbox.enqueue(me, PushMessage.fillCompleted("K7X2MQ"), EnqueueOptions.dedup("fc:retry"));

        relay.runOnce(NOW_LDT);
        NotificationOutbox after1 = rows.findByDedupKey("fc:retry").orElseThrow();
        assertThat(after1.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after1.getAttempts()).isEqualTo(1);
        assertThat(after1.getNotBefore()).isEqualTo(NOW_LDT.plusMinutes(1));
        assertThat(after1.getLastError()).contains("push failed");

        assertThat(relay.runOnce(NOW_LDT.plusSeconds(30))).isZero();      // 아직 때가 아니다
        assertThat(relay.runOnce(NOW_LDT.plusMinutes(2))).isEqualTo(1);   // 때가 됐다

        NotificationOutbox done = rows.findByDedupKey("fc:retry").orElseThrow();
        assertThat(done.getStatus()).isEqualTo(OutboxStatus.SENT);
        verify(pushService, times(2)).sendReport(eq(me), any());
        assertThat(inbox.count()).isEqualTo(1);
        assertThat(meters.counter("notification.relay.processed", "type", "fill_completed", "result", "retried").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("보류함: 한도(여기선 2회)만큼 실패하면 DEAD 가 되고 더는 시도하지 않는다")
    void deadAfterMaxAttempts() {
        given(pushService.sendReport(eq(me), any())).willReturn(FAIL);
        outbox.enqueue(me, PushMessage.fillCompleted("K7X2MQ"), EnqueueOptions.dedup("fc:dead"));

        relay.runOnce(NOW_LDT);
        relay.runOnce(NOW_LDT.plusMinutes(2));
        NotificationOutbox row = rows.findByDedupKey("fc:dead").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(relay.runOnce(NOW_LDT.plusHours(1))).isZero();
        verify(pushService, times(2)).sendReport(eq(me), any());
        assertThat(meters.counter("notification.relay.processed", "type", "fill_completed", "result", "dead").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("유효 시한: expiresAt 이 지난 행은 보내지 않고 EXPIRED 로 끝낸다(알림함에도 안 남는다)")
    void expiredIsNotSent() {
        outbox.enqueue(me, PushMessage.fillExpiring("K7X2MQ", "제주"),
                EnqueueOptions.dedup("fe:1").expiring(NOW_LDT.minusMinutes(1)));

        relay.runOnce(NOW_LDT);

        assertThat(rows.findByDedupKey("fe:1").orElseThrow().getStatus()).isEqualTo(OutboxStatus.EXPIRED);
        verify(pushService, never()).sendReport(anyLong(), any());
        assertThat(inbox.count()).isZero();
    }

    @Test
    @DisplayName("기기가 없는 사용자: 알림함에만 남기고 SENT 로 끝낸다(재시도 대상이 아니다)")
    void noDevicesIsStillSent() {
        given(pushService.sendReport(eq(me), any())).willReturn(new PushService.SendReport(0, 0, 0, 0));
        outbox.enqueue(me, PushMessage.fillCompleted("K7X2MQ"), EnqueueOptions.dedup("fc:nodev"));

        relay.runOnce(NOW_LDT);

        assertThat(rows.findByDedupKey("fc:nodev").orElseThrow().getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(inbox.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("죽은 릴레이가 쥔 행(lease 지남)은 다음 바퀴에 되찾아 보낸다")
    void staleLeaseIsReclaimed() {
        given(pushService.sendReport(eq(me), any())).willReturn(OK);
        rows.save(NotificationOutbox.builder().userId(me).type("fill_completed").title("t").body("b").data("{}")
                .dedupKey("fc:stale").notBefore(NOW_LDT.minusMinutes(5)).status(OutboxStatus.PROCESSING)
                .attempts(0).claimToken("dead-relay").leaseUntil(NOW_LDT.minusSeconds(1)).build());

        assertThat(relay.runOnce(NOW_LDT)).isEqualTo(1);

        assertThat(rows.findByDedupKey("fc:stale").orElseThrow().getStatus()).isEqualTo(OutboxStatus.SENT);
        verify(pushService).sendReport(eq(me), any());
    }

    @Test
    @DisplayName("릴레이 둘이 동시에 돌아도 한 행은 한 번만 보낸다(행 단위 집기)")
    void concurrentRelaysDoNotDoubleSend() throws Exception {
        given(pushService.sendReport(eq(me), any())).willReturn(OK);
        for (int i = 0; i < 20; i++) {
            outbox.enqueue(me, PushMessage.fillCompleted("C" + i), EnqueueOptions.dedup("fc:conc:" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = pool.invokeAll(List.of(() -> relay.runOnce(NOW_LDT), () -> relay.runOnce(NOW_LDT)));
            int processed = 0;
            for (Future<Integer> f : runs) processed += f.get(30, TimeUnit.SECONDS);
            assertThat(processed).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
        verify(pushService, times(20)).sendReport(eq(me), any());
        assertThat(rows.countByStatus(OutboxStatus.SENT)).isEqualTo(20);
    }

    @Test
    @DisplayName("사건 → 행: 네 칸 완성은 주인 + 토큰 있는 참여자, 문의 답변은 1건, 같은 사건이 두 번 와도 행은 그대로")
    void domainEventsBecomeRows() {
        given(tokens.existsByUserIdAndDisabledAtIsNull(11L)).willReturn(true);
        given(tokens.existsByUserIdAndDisabledAtIsNull(12L)).willReturn(false);
        FillCompletedEvent completed = new FillCompletedEvent(1L, me, "K7X2MQ", List.of(11L, 12L));

        enqueuer.onFillCompleted(completed);
        enqueuer.onFillCompleted(completed);
        enqueuer.onSupportReplied(new SupportRepliedEvent(5L, me, "제목", 2));

        assertThat(rows.count()).isEqualTo(3);
        assertThat(rows.findByDedupKey("fill_completed:1:" + me)).isPresent();
        assertThat(rows.findByDedupKey("fill_completed:1:11")).isPresent();
        assertThat(rows.findByDedupKey("fill_completed:1:12")).isEmpty();
        assertThat(rows.findByDedupKey("support_reply:5:2").orElseThrow().getType()).isEqualTo("support_reply");
    }

    @Test
    @DisplayName("정리: 끝난 지 7일 넘은 SENT·EXPIRED 는 지우고, DEAD 는 30일, 대기 중인 것은 두는다")
    void cleanupKeepsPendingAndRecent() {
        rows.save(row("old-sent", OutboxStatus.SENT, NOW_LDT.minusDays(8)));
        rows.save(row("new-sent", OutboxStatus.SENT, NOW_LDT.minusDays(1)));
        rows.save(row("old-dead", OutboxStatus.DEAD, NOW_LDT.minusDays(31)));
        rows.save(row("young-dead", OutboxStatus.DEAD, NOW_LDT.minusDays(8)));
        rows.save(row("pending", OutboxStatus.PENDING, null));

        // 운영에서는 @Transactional 프록시가 감싼다. 여기선 직접 만들었으니 템플릿으로 감싼다
        OutboxCleanupScheduler cleanup = new OutboxCleanupScheduler(rows, clock, 7, 30);
        int deleted = tx.execute(s -> cleanup.runOnce(NOW_LDT));

        assertThat(deleted).isEqualTo(2);
        assertThat(rows.findByDedupKey("old-sent")).isEmpty();
        assertThat(rows.findByDedupKey("old-dead")).isEmpty();
        assertThat(rows.findByDedupKey("new-sent")).isPresent();
        assertThat(rows.findByDedupKey("young-dead")).isPresent();
        assertThat(rows.findByDedupKey("pending")).isPresent();
    }

    private NotificationOutbox row(String key, OutboxStatus status, LocalDateTime sentAt) {
        return NotificationOutbox.builder().userId(me).type("fill_completed").title("t").body("b").data("{}")
                .dedupKey(key).notBefore(NOW_LDT).status(status).attempts(0).sentAt(sentAt).build();
    }
}
