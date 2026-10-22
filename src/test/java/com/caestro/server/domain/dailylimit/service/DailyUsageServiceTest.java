package com.caestro.server.domain.dailylimit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.caestro.server.domain.dailylimit.dto.response.DailyUsageResponse;
import com.caestro.server.domain.dailylimit.entity.DailyUsage;
import com.caestro.server.domain.dailylimit.enums.DailyAction;
import com.caestro.server.domain.dailylimit.repository.DailyUsageRepository;
import com.caestro.server.domain.subscription.service.SubscriptionService;
import com.caestro.server.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class DailyUsageServiceTest {

    private static final Long USER_ID = 3L;
    private static final DailyAction ACTION = DailyAction.FOURCUT_SAVE;
    // 서버 시계는 UTC. 2026-10-22 15:30Z 는 서울로 10월 23일 00:30 이다
    private static final Instant NOW = Instant.parse("2026-10-22T15:30:00Z");
    private static final LocalDate SEOUL_TODAY = LocalDate.of(2026, 10, 23);

    @Mock
    private DailyUsageRepository repository;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PlatformTransactionManager transactionManager;

    private DailyUsageService service;

    @BeforeEach
    void setUp() {
        service = new DailyUsageService(repository, subscriptionService, userRepository, transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Seoul", 3);
    }

    @Test
    @DisplayName("프리미엄 구독 중이면 한도 없이 허용하고 기록하지 않는다")
    void premiumIsUnlimited() {
        given(subscriptionService.isActivePremium(USER_ID)).willReturn(true);

        DailyUsageResponse status = service.status(USER_ID, ACTION);
        DailyUsageResponse consumed = service.consume(USER_ID, ACTION, "p1");

        assertThat(status.unlimited()).isTrue();
        assertThat(consumed.allowed()).isTrue();
        assertThat(consumed.unlimited()).isTrue();
        assertThat(consumed.remaining()).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("날은 서울 자정에 바뀐다 (UTC 15:30 = 서울 다음 날 00:30)")
    void daysFollowSeoul() {
        given(repository.countByUserIdAndUsageDateAndActionType(USER_ID, SEOUL_TODAY, ACTION)).willReturn(1L);

        DailyUsageResponse status = service.status(USER_ID, ACTION);

        assertThat(status.date()).isEqualTo(SEOUL_TODAY);
        assertThat(status.resetsAt()).isEqualTo(OffsetDateTime.parse("2026-10-24T00:00:00+09:00"));
        assertThat(status.used()).isEqualTo(1);
        assertThat(status.remaining()).isEqualTo(2);
        assertThat(status.allowed()).isTrue();
    }

    @Test
    @DisplayName("처음 쓰면 다음 순번으로 기록하고 남은 횟수가 줄어든다")
    void consumeRecordsNextSlot() {
        given(repository.existsByUserIdAndUsageDateAndActionTypeAndUsageKey(USER_ID, SEOUL_TODAY, ACTION, "p1")).willReturn(false);
        given(repository.countByUserIdAndUsageDateAndActionType(USER_ID, SEOUL_TODAY, ACTION)).willReturn(1L, 2L);

        DailyUsageResponse response = service.consume(USER_ID, ACTION, "p1");

        ArgumentCaptor<DailyUsage> saved = ArgumentCaptor.forClass(DailyUsage.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSlot()).isEqualTo(2);
        assertThat(saved.getValue().getUsageKey()).isEqualTo("p1");
        assertThat(saved.getValue().getUsageDate()).isEqualTo(SEOUL_TODAY);
        assertThat(response.allowed()).isTrue();
        assertThat(response.used()).isEqualTo(2);
        assertThat(response.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 센 네컷은 다시 세지 않고 허용한다 (재시도·재저장)")
    void sameKeyCountsOnce() {
        given(repository.existsByUserIdAndUsageDateAndActionTypeAndUsageKey(USER_ID, SEOUL_TODAY, ACTION, "p1")).willReturn(true);
        given(repository.countByUserIdAndUsageDateAndActionType(USER_ID, SEOUL_TODAY, ACTION)).willReturn(3L);

        DailyUsageResponse response = service.consume(USER_ID, ACTION, "p1");

        assertThat(response.allowed()).isTrue();
        assertThat(response.remaining()).isZero();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("한도가 차면 기록하지 않고 allowed=false")
    void limitReached() {
        given(repository.existsByUserIdAndUsageDateAndActionTypeAndUsageKey(USER_ID, SEOUL_TODAY, ACTION, "p4")).willReturn(false);
        given(repository.countByUserIdAndUsageDateAndActionType(USER_ID, SEOUL_TODAY, ACTION)).willReturn(3L);

        DailyUsageResponse response = service.consume(USER_ID, ACTION, "p4");

        assertThat(response.allowed()).isFalse();
        assertThat(response.used()).isEqualTo(3);
        assertThat(response.remaining()).isZero();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동시에 같은 순번을 쓰면 유니크 충돌 뒤 새로 읽어 다음 순번으로 기록한다")
    void conflictRetries() {
        given(repository.existsByUserIdAndUsageDateAndActionTypeAndUsageKey(eq(USER_ID), eq(SEOUL_TODAY), eq(ACTION), anyString()))
                .willReturn(false);
        // 첫 시도: 1개 쓴 줄 알고 순번 2 → 다른 요청이 먼저 순번 2를 씀(충돌). 다시 보니 2개 → 순번 3. 끝나고 3개
        given(repository.countByUserIdAndUsageDateAndActionType(USER_ID, SEOUL_TODAY, ACTION)).willReturn(1L, 2L, 3L);
        given(repository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("uk_daily_usages_slot"))
                .willAnswer(inv -> inv.getArgument(0));

        DailyUsageResponse response = service.consume(USER_ID, ACTION, "p3");

        ArgumentCaptor<DailyUsage> saved = ArgumentCaptor.forClass(DailyUsage.class);
        verify(repository, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().get(1).getSlot()).isEqualTo(3);
        assertThat(response.allowed()).isTrue();
        assertThat(response.remaining()).isZero();
    }
}
