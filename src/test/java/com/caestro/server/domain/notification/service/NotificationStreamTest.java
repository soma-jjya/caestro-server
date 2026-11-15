package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.notification.entity.NotificationOutbox;
import com.caestro.server.domain.notification.entity.OutboxStatus;
import com.caestro.server.domain.notification.repository.NotificationOutboxRepository;
import com.caestro.server.domain.notification.repository.NotificationRepository;
import com.caestro.server.domain.push.entity.PushToken;
import com.caestro.server.domain.push.enums.PushPlatform;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.sender.PushResult;
import com.caestro.server.domain.push.sender.PushSender;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * #273 — 알림 전달을 Redis 스트림으로: 실제 MySQL·Redis 로 아웃박스 → 릴레이(XADD) → 소비자 그룹(XREADGROUP) → FCM(목) 을 끝까지 돈다.
 * 재시도·보류함은 짧은 간격(1초)으로, 스트림 키는 실행마다 새로. 이 클래스만 워커를 켜고, 끝나면 컨텍스트를 닫는다(다른 테스트의 행을 가져가지 않게).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationStreamTest {

    static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void streamProperties(DynamicPropertyRegistry r) {
        r.add("notification.worker.enabled", () -> "true");
        r.add("notification.stream.enabled", () -> "true");
        r.add("notification.stream.key", () -> "notifications-test-" + RUN);
        r.add("notification.stream.dead-key", () -> "notifications-test-" + RUN + ".dead");
        r.add("notification.stream.claim-idle-seconds", () -> "1");
        r.add("notification.stream.sweep-ms", () -> "300");
        r.add("notification.outbox.poll-ms", () -> "200");
        r.add("notification.outbox.backoff", () -> "1s,1s,1s");
        r.add("notification.outbox.max-attempts", () -> "3");
    }

    @Autowired NotificationOutboxService outbox;
    @Autowired NotificationOutboxRepository rows;
    @Autowired NotificationRepository inbox;
    @Autowired NotificationStreamPublisher publisher;
    @Autowired NotificationStreamConsumer consumer;
    @Autowired OutboxSettings settings;
    @Autowired UserRepository users;
    @Autowired PushTokenRepository tokens;
    @Autowired TransactionTemplate tx;
    @Autowired RedisTemplate<String, String> redisTemplate;
    @MockitoBean PushSender sender;

    User user;
    PushToken token;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user = users.save(User.builder().deviceId("stream-" + suffix).role(User.Role.USER).build());
        token = tokens.save(PushToken.builder().user(user).platform(PushPlatform.ANDROID).token("tok-" + suffix)
                .lastSeenAt(LocalDateTime.now()).build());
        given(sender.configured()).willReturn(true);
    }

    @AfterEach
    void cleanup() {
        rows.findAll().stream().filter(r -> r.getUserId().equals(user.getId())).forEach(rows::delete);
        inbox.deleteAll(inbox.findByUserIdOrderByIdDesc(user.getId(), PageRequest.of(0, 50)));
        tokens.delete(token);
        users.delete(user);
    }

    private NotificationOutbox enqueue(String key) {
        tx.executeWithoutResult(s -> outbox.enqueue(user.getId(), PushMessage.fillCompleted("K7X2MQ"), EnqueueOptions.dedup(key + RUN)));
        return rows.findByDedupKey(key + RUN).orElseThrow();
    }

    private NotificationOutbox reload(NotificationOutbox row) {
        return rows.findById(row.getId()).orElseThrow();
    }

    private void awaitStatus(NotificationOutbox row, OutboxStatus status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (reload(row).getStatus() == status) return;
            Thread.sleep(100);
        }
        assertThat(reload(row).getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("행 → 릴레이 XADD → 소비자 → FCM 한 번, 행 SENT, 그룹에 끝내지 못한 메시지 없음")
    void deliversThroughTheStream() throws Exception {
        given(sender.send(anyString(), any())).willReturn(PushResult.SENT);
        NotificationOutbox row = enqueue("stream:ok:");

        verify(sender, timeout(5000).times(1)).send(eq(token.getToken()), any());
        awaitStatus(row, OutboxStatus.SENT);
        assertThat(reload(row).getNotificationId()).isNotNull();
        assertThat(redisTemplate.opsForStream().pending(settings.streamKey(), settings.group()).getTotalPendingMessages()).isZero();
    }

    @Test
    @DisplayName("FCM 이 두 번 실패하면 ack 하지 않고 PEL 에 남았다가 스윕이 다시 가져가 세 번째에 성공 — 알림함은 한 줄")
    void retriesFromThePendingList() throws Exception {
        given(sender.send(anyString(), any())).willReturn(PushResult.FAILED, PushResult.FAILED, PushResult.SENT);
        NotificationOutbox row = enqueue("stream:retry:");

        verify(sender, timeout(12_000).times(3)).send(eq(token.getToken()), any());
        awaitStatus(row, OutboxStatus.SENT);
        assertThat(reload(row).getAttempts()).isEqualTo(2);
        assertThat(inbox.countByUserIdAndReadAtIsNull(user.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("한도(3회) 실패면 보류함 스트림에 남기고 행 DEAD, 더는 보내지 않는다")
    void deadLettersAfterMaxAttempts() throws Exception {
        given(sender.send(anyString(), any())).willReturn(PushResult.FAILED);
        NotificationOutbox row = enqueue("stream:dead:");

        awaitStatus(row, OutboxStatus.DEAD);
        assertThat(reload(row).getAttempts()).isEqualTo(3);
        assertThat(redisTemplate.opsForStream().size(settings.deadKey())).isGreaterThanOrEqualTo(1);
        verify(sender, after(2500).times(3)).send(eq(token.getToken()), any());
    }

    @Test
    @DisplayName("같은 행의 메시지가 두 번 들어와도(재전송·중복 XADD) 한 번만 보낸다")
    void duplicateMessageIsSentOnce() throws Exception {
        given(sender.send(anyString(), any())).willReturn(PushResult.SENT);
        NotificationOutbox row = enqueue("stream:dup:");
        awaitStatus(row, OutboxStatus.SENT);

        publisher.publish(row.getId());

        verify(sender, after(1500).times(1)).send(eq(token.getToken()), any());
    }

    @Test
    @DirtiesContext
    @DisplayName("소비자가 꺼낸 뒤 ack 전에 죽으면(유령 소비자) 1초 뒤 스윕이 XCLAIM 으로 넘겨받아 보낸다")
    void reclaimsFromADeadConsumer() throws Exception {
        consumer.stop();   // 살아 있는 소비자가 먼저 가져가지 않게
        Thread.sleep(Duration.ofMillis(2600));   // 이미 걸려 있던 블로킹 읽기(pollTimeout 2초)가 끝나길 기다린다
        given(sender.send(anyString(), any())).willReturn(PushResult.SENT);
        NotificationOutbox row = rows.save(NotificationOutbox.builder().userId(user.getId()).type(PushMessage.TYPE_FILL_COMPLETED)
                .title("t").body("b").data("{\"code\":\"K7X2MQ\"}").dedupKey("stream:ghost:" + RUN)
                .notBefore(LocalDateTime.now()).status(OutboxStatus.RELAYED).leaseUntil(LocalDateTime.now().plusHours(1)).attempts(0).build());
        publisher.publish(row.getId());
        // 유령 소비자가 꺼내고 죽는다(ack 없음)
        redisTemplate.opsForStream().read(Consumer.from(settings.group(), "ghost"), StreamReadOptions.empty().count(10),
                StreamOffset.create(settings.streamKey(), ReadOffset.lastConsumed()));

        Thread.sleep(Duration.ofMillis(1200));
        assertThat(consumer.sweepOnce()).isEqualTo(1);

        verify(sender, timeout(3000).times(1)).send(eq(token.getToken()), any());
        assertThat(reload(row).getStatus()).isEqualTo(OutboxStatus.SENT);
    }
}
