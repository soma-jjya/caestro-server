package com.caestro.server.domain.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.domain.notification.entity.NotificationOutbox;
import com.caestro.server.domain.notification.entity.OutboxStatus;
import com.caestro.server.domain.notification.repository.NotificationOutboxRepository;
import com.caestro.server.domain.notification.repository.NotificationRepository;
import com.caestro.server.domain.notification.service.NotificationOutboxService;
import com.caestro.server.domain.notification.service.OutboxEnqueuer;
import com.caestro.server.domain.notification.service.OutboxRelay;
import com.caestro.server.domain.push.entity.PushToken;
import com.caestro.server.domain.push.enums.PushPlatform;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.sender.PushResult;
import com.caestro.server.domain.push.sender.PushSender;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #274 — 친구가 칸을 채우면 주인에게 묶음 알림 "친구가 네컷을 채웠어요". 세션별 한 행에 합쳐 5분 조용하면 한 통, 한 시간에 한 통,
 * 완성되면 취소. 합치기는 MySQL upsert 라 실제 MySQL 필요 (docker-compose up -d / CI 서비스 컨테이너). FCM 은 목, 릴레이는 직접 모드로 한 바퀴씩.
 */
// RANDOM_PORT: 모의 서블릿 환경에는 WS ServerContainer가 없어 WS 설정 빈이 초기화되지 못한다 (contextLoads와 동일)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "notification.stream.enabled=false")
class FillProgressPushTest {

    private static final Duration QUIET = Duration.ofMinutes(5);
    private static final Duration WINDOW = Duration.ofHours(1);

    @Autowired private FillService fillService;
    @Autowired private FillSessionRepository sessionRepository;
    @Autowired private PushTokenRepository tokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationOutboxRepository outboxRepository;
    @Autowired private NotificationOutboxService outbox;
    @Autowired private OutboxRelay relay;

    @MockitoBean private PushSender sender;

    private User owner;
    private User friend;
    private User friend2;
    private PushToken token;
    private String code;
    private String progressKey;

    private static byte[] jpeg(byte filler) {
        byte[] bytes = new byte[512];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        for (int i = 4; i < bytes.length; i++) {
            bytes[i] = filler;
        }
        return bytes;
    }

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.save(User.builder().deviceId("progress-owner-" + suffix).nickname("주인").role(User.Role.USER).build());
        friend = userRepository.save(User.builder().deviceId("progress-friend-" + suffix).nickname("민지").role(User.Role.USER).build());
        friend2 = userRepository.save(User.builder().deviceId("progress-friend2-" + suffix).nickname("서준").role(User.Role.USER).build());
        token = tokenRepository.save(PushToken.builder().user(owner).platform(PushPlatform.ANDROID)
                .token("tok-progress-" + suffix).lastSeenAt(LocalDateTime.now()).build());
        given(sender.configured()).willReturn(true);
        given(sender.send(anyString(), any())).willReturn(PushResult.SENT);
        code = fillService.create(owner.getId(), new CreateFillRequest("Black", "Strip", null, null, "제주 여행", null)).code();
        progressKey = OutboxEnqueuer.progressKey(sessionRepository.findByCode(code).orElseThrow().getId(), owner.getId());
    }

    @AfterEach
    void cleanup() {
        outboxRepository.deleteAll(outboxRepository.findAllByCollapseKeyOrderByIdAsc(progressKey));
        try {
            fillService.delete(code, owner.getId());
        } catch (CustomException ignored) {
            // 이미 지워졌으면 넘어간다
        }
        tokenRepository.delete(token);
        notificationRepository.deleteAll(notificationRepository.findByUserIdOrderByIdDesc(owner.getId(), PageRequest.of(0, 20)));
        userRepository.deleteAll(List.of(owner, friend, friend2));
    }

    private List<NotificationOutbox> progressRows() {
        return outboxRepository.findAllByCollapseKeyOrderByIdAsc(progressKey);
    }

    private PushMessage onlyPushToOwner() {
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(sender, times(1)).send(eq(token.getToken()), message.capture());
        return message.getValue();
    }

    @Test
    @DisplayName("친구가 세 칸을 연달아 채우면 행은 하나(합친 수 3), 5분 조용해지기 전엔 안 가고, 그 뒤 주인에게 한 통 — 문구는 보내는 순간의 칸 수")
    void threeFills_oneNotificationAfterQuiet() {
        LocalDateTime start = LocalDateTime.now();
        for (int i = 0; i < 3; i++) {
            fillService.fillSlot(code, i, friend.getId(), "민지", jpeg((byte) i), "image/jpeg");
        }

        List<NotificationOutbox> rows = progressRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getMergedCount()).isEqualTo(3);
        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);

        relay.runOnce(start.plusMinutes(3));
        verify(sender, never()).send(eq(token.getToken()), any());

        relay.runOnce(start.plusMinutes(6));
        PushMessage sent = onlyPushToOwner();
        assertThat(sent.type()).isEqualTo(PushMessage.TYPE_FILL_PROGRESS);
        assertThat(sent.title()).isEqualTo("민지님이 네컷을 채웠어요");
        assertThat(sent.body()).isEqualTo("'제주 여행' 4칸 중 3칸이 찼어요. 1칸만 더 채우면 완성이에요!");
        assertThat(sent.data()).containsEntry("code", code).containsKey(PushMessage.DATA_NOTIFICATION_ID);
        // 행에도 실제로 나간 문구가 남고, 알림함에는 한 줄
        NotificationOutbox row = progressRows().get(0);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getTitle()).isEqualTo(sent.title());
        assertThat(notificationRepository.countByUserIdAndReadAtIsNull(owner.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("묶이는 동안 네 칸이 다 차면 진행 알림은 취소되고 주인은 '완성됐어요' 한 통만 받는다")
    void completedWhileWaiting_onlyCompletedNotification() {
        LocalDateTime start = LocalDateTime.now();
        for (int i = 0; i < 4; i++) {
            fillService.fillSlot(code, i, friend.getId(), "민지", jpeg((byte) i), "image/jpeg");
        }
        assertThat(progressRows()).singleElement().extracting(NotificationOutbox::getStatus).isEqualTo(OutboxStatus.CANCELLED);

        relay.runOnce(start.plusMinutes(11));
        assertThat(onlyPushToOwner().type()).isEqualTo(PushMessage.TYPE_FILL_COMPLETED);
    }

    @Test
    @DisplayName("주인이 직접 채운 칸은 알림을 만들지 않고, 친구 몫에서도 빠진다 — 칸 수에는 든다")
    void ownerFills_notNotified() {
        LocalDateTime start = LocalDateTime.now();
        fillService.fillSlot(code, 0, owner.getId(), null, jpeg((byte) 0), "image/jpeg");
        fillService.fillSlot(code, 1, owner.getId(), null, jpeg((byte) 1), "image/jpeg");
        assertThat(progressRows()).isEmpty();

        fillService.fillSlot(code, 2, friend.getId(), "민지", jpeg((byte) 2), "image/jpeg");
        assertThat(progressRows()).singleElement().extracting(NotificationOutbox::getMergedCount).isEqualTo(1);

        relay.runOnce(start.plusMinutes(11));
        PushMessage sent = onlyPushToOwner();
        assertThat(sent.title()).isEqualTo("민지님이 네컷을 채웠어요");
        assertThat(sent.body()).isEqualTo("'제주 여행' 4칸 중 3칸이 찼어요. 1칸만 더 채우면 완성이에요!");
    }

    @Test
    @DisplayName("[동시성] 친구 둘이 같은 순간 다른 칸을 채워도 묶음 행은 하나(합친 수 2), 둘 다 성공하고 제목은 '외 1명'")
    void concurrentFills_oneRow() throws Exception {
        LocalDateTime start = LocalDateTime.now();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> { await(go); fillService.fillSlot(code, 0, friend.getId(), "민지", jpeg((byte) 0), "image/jpeg"); });
            Future<?> b = pool.submit(() -> { await(go); fillService.fillSlot(code, 1, friend2.getId(), "서준", jpeg((byte) 1), "image/jpeg"); });
            go.countDown();
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(progressRows()).singleElement().extracting(NotificationOutbox::getMergedCount).isEqualTo(2);

        relay.runOnce(start.plusMinutes(11));
        PushMessage sent = onlyPushToOwner();
        assertThat(sent.title()).endsWith("님 외 1명이 네컷을 채웠어요");
        assertThat(sent.body()).isEqualTo("'제주 여행' 4칸 중 2칸이 찼어요. 2칸만 더 채우면 완성이에요!");
    }

    @Test
    @DisplayName("1시간 넘게 계속 채우면 처음부터 1시간에 한 통 가고, 보낸 뒤 1시간 안의 채움은 다음 통으로 미뤄진다")
    void steadyFilling_atMostOnePerHour() {
        fillService.fillSlot(code, 0, friend.getId(), "민지", jpeg((byte) 0), "image/jpeg");
        NotificationOutbox first = progressRows().get(0);
        LocalDateTime created = first.getCreatedAt();
        // 4분마다 채움이 64분 동안 이어진다 — 매번 5분씩 늦추지만 처음 + 1시간에서 멈춘다
        for (int minute = 4; minute <= 64; minute += 4) {
            outbox.enqueueCollapsed(owner.getId(), PushMessage.fillProgressPlaceholder(code), progressKey, created.plusMinutes(minute), QUIET, WINDOW);
        }
        NotificationOutbox merged = progressRows().get(0);
        assertThat(merged.getMergedCount()).isEqualTo(17);
        assertThat(merged.getNotBefore()).isEqualTo(created.plus(WINDOW));

        relay.runOnce(created.plus(WINDOW));
        onlyPushToOwner();
        clearInvocations(sender);

        // 보낸 지 1분 뒤 또 채움 → 새 묶음이지만 보낸 때 + 1시간까지 기다린다
        outbox.enqueueCollapsed(owner.getId(), PushMessage.fillProgressPlaceholder(code), progressKey, created.plusMinutes(61), QUIET, WINDOW);
        List<NotificationOutbox> rows = progressRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).getNotBefore()).isEqualTo(created.plus(WINDOW).plus(WINDOW));

        relay.runOnce(created.plusMinutes(75));
        verify(sender, never()).send(eq(token.getToken()), any());
        relay.runOnce(created.plus(WINDOW).plus(WINDOW));
        onlyPushToOwner();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
