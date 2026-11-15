package com.caestro.server.domain.fill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.notification.service.EnqueueOptions;
import com.caestro.server.domain.notification.service.NotificationOutboxService;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.user.entity.User;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 만료 전날 안내 배치(#213): 창 안 대상에 세션당 1회, 표시를 못 잡으면 건너뛰고, 하나가 실패해도 나머지는 간다. */
@ExtendWith(MockitoExtension.class)
class FillExpiringNotifierTest {

    // UTC 00:00 = KST 09:00 — 배치가 도는 시각
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock private FillSessionRepository sessionRepository;
    @Mock private NotificationOutboxService outbox;

    private SimpleMeterRegistry registry;
    private FillExpiringNotifier notifier;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        notifier = new FillExpiringNotifier(sessionRepository, outbox,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC), registry);
    }

    private static FillSession session(long id, String code, String title, LocalDateTime expiresAt) {
        User owner = User.builder().deviceId("d-" + id).role(User.Role.USER).build();
        ReflectionTestUtils.setField(owner, "id", 100 + id);
        FillSession s = FillSession.builder().code(code).owner(owner).frame("Black").layout("Strip").title(title)
                .expiresAt(expiresAt).build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Test
    @DisplayName("창 (now, now+24h] 로 대상을 읽고, 표시를 잡은 세션에만 fill_expiring 을 보낸다 — 제목이 본문에 들어간다")
    void notifiesClaimedTargetsOnce() {
        FillSession titled = session(1, "AAAA11", "제주 여행", NOW_LOCAL.plusHours(10));
        FillSession untitled = session(2, "BBBB22", null, NOW_LOCAL.plusHours(20));
        given(sessionRepository.findExpiringTargets(NOW_LOCAL, NOW_LOCAL.plusHours(24))).willReturn(List.of(titled, untitled));
        given(sessionRepository.markExpiringNotified(1L, NOW_LOCAL)).willReturn(1);
        given(sessionRepository.markExpiringNotified(2L, NOW_LOCAL)).willReturn(0);   // 다른 인스턴스가 먼저 잡음

        FillExpiringNotifier.Result result = notifier.runOnce(NOW_LOCAL);

        assertThat(result).isEqualTo(new FillExpiringNotifier.Result(2, 1, 1, 0));
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        ArgumentCaptor<EnqueueOptions> options = ArgumentCaptor.forClass(EnqueueOptions.class);
        verify(outbox).enqueue(eq(101L), message.capture(), options.capture());
        verify(outbox, never()).enqueue(eq(102L), any(), any());
        assertThat(message.getValue().type()).isEqualTo(PushMessage.TYPE_FILL_EXPIRING);
        assertThat(message.getValue().data()).containsEntry("code", "AAAA11");
        assertThat(message.getValue().title()).isEqualTo("네컷 사진이 곧 만료돼요!");
        assertThat(message.getValue().body()).isEqualTo("'제주 여행' 사진이 내일 사라져요. 지금 저장해 두세요.");
        // #272: 세션당 한 번(dedup), 세션이 만료되면 보내지 않는다(expiresAt)
        assertThat(options.getValue().dedupKey()).isEqualTo("fill_expiring:1");
        assertThat(options.getValue().expiresAt()).isEqualTo(NOW_LOCAL.plusHours(10));
        assertThat(registry.counter("fill.expiring.notified").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("한 세션의 발송이 실패해도 나머지는 가고, 실패는 지표와 결과에 남는다")
    void oneFailureDoesNotStopTheRest() {
        FillSession first = session(1, "AAAA11", null, NOW_LOCAL.plusHours(1));
        FillSession second = session(2, "BBBB22", null, NOW_LOCAL.plusHours(2));
        given(sessionRepository.findExpiringTargets(any(), any())).willReturn(List.of(first, second));
        given(sessionRepository.markExpiringNotified(any(), any())).willReturn(1);
        given(outbox.enqueue(eq(101L), any(), any())).willThrow(new IllegalStateException("boom"));
        given(outbox.enqueue(eq(102L), any(), any())).willReturn(true);

        FillExpiringNotifier.Result result = notifier.runOnce(NOW_LOCAL);

        assertThat(result).isEqualTo(new FillExpiringNotifier.Result(2, 1, 0, 1));
        assertThat(registry.counter("fill.expiring.failed").count()).isEqualTo(1.0);
        // 제목이 없으면 코드로 부른다
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(outbox).enqueue(eq(102L), message.capture(), any());
        assertThat(message.getValue().body()).startsWith("'BBBB22' 사진이");
    }
}
