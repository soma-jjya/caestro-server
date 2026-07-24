package com.caestro.server.domain.dailylimit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.dailylimit.entity.ShotDailyLimit;
import com.caestro.server.domain.dailylimit.repository.ShotDailyLimitRepository;
import com.caestro.server.domain.subscription.service.SubscriptionService;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DailyLimitServiceTest {

    @Mock
    private ShotDailyLimitRepository shotDailyLimitRepository;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private UserRepository userRepository;

    private DailyLimitService dailyLimitService;

    private static final Long USER_ID = 1L;
    private static final String ACTION = "GHOST_GUIDE";
    private static final int LIMIT = 5;
    // 날짜 고정: 2026-03-15 (UTC)
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() {
        dailyLimitService = new DailyLimitService(shotDailyLimitRepository, subscriptionService, userRepository, FIXED_CLOCK);
    }

    private ShotDailyLimit limitWithCount(int count) {
        return ShotDailyLimit.builder()
                .user(User.builder().oauthProvider("t").oauthId("u").role(User.Role.USER).build())
                .date(TODAY)
                .count(count)
                .build();
    }

    @Test
    @DisplayName("한도 이내: 통과하고 count를 1 증가시킨다")
    void withinLimit_incrementsAndPasses() {
        given(subscriptionService.isActivePremium(USER_ID)).willReturn(false);
        ShotDailyLimit existing = limitWithCount(2);
        given(shotDailyLimitRepository.findByUserIdAndDate(USER_ID, TODAY)).willReturn(Optional.of(existing));

        boolean result = dailyLimitService.checkAndIncrementDailyLimit(USER_ID, ACTION, LIMIT);

        assertThat(result).isTrue();
        assertThat(existing.getCount()).isEqualTo(3);
        verify(shotDailyLimitRepository).save(existing);
    }

    @Test
    @DisplayName("한도 초과: 차단하고 count를 증가시키지 않는다")
    void overLimit_blockedNoIncrement() {
        given(subscriptionService.isActivePremium(USER_ID)).willReturn(false);
        ShotDailyLimit existing = limitWithCount(5);
        given(shotDailyLimitRepository.findByUserIdAndDate(USER_ID, TODAY)).willReturn(Optional.of(existing));

        boolean result = dailyLimitService.checkAndIncrementDailyLimit(USER_ID, ACTION, LIMIT);

        assertThat(result).isFalse();
        assertThat(existing.getCount()).isEqualTo(5);
        verify(shotDailyLimitRepository, never()).save(any());
    }

    @Test
    @DisplayName("활성 프리미엄 구독자: 한도 체크를 우회하고 카운터를 건드리지 않는다")
    void premium_bypasses() {
        given(subscriptionService.isActivePremium(USER_ID)).willReturn(true);

        boolean result = dailyLimitService.checkAndIncrementDailyLimit(USER_ID, ACTION, LIMIT);

        assertThat(result).isTrue();
        verify(shotDailyLimitRepository, never()).findByUserIdAndDate(any(), any());
        verify(shotDailyLimitRepository, never()).save(any());
    }

    @Test
    @DisplayName("오늘자 row가 없으면 count=0으로 새로 만들고 1로 증가시킨다 (날짜 바뀌면 새 카운트)")
    void newDay_createsFreshCounter() {
        given(subscriptionService.isActivePremium(USER_ID)).willReturn(false);
        given(shotDailyLimitRepository.findByUserIdAndDate(USER_ID, TODAY)).willReturn(Optional.empty());
        given(userRepository.getReferenceById(USER_ID))
                .willReturn(User.builder().oauthProvider("t").oauthId("u").role(User.Role.USER).build());

        boolean result = dailyLimitService.checkAndIncrementDailyLimit(USER_ID, ACTION, LIMIT);

        assertThat(result).isTrue();
        ArgumentCaptor<ShotDailyLimit> captor = ArgumentCaptor.forClass(ShotDailyLimit.class);
        verify(shotDailyLimitRepository).save(captor.capture());
        assertThat(captor.getValue().getDate()).isEqualTo(TODAY);
        assertThat(captor.getValue().getCount()).isEqualTo(1);
    }
}
