package com.caestro.server.domain.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.subscription.dto.request.CreateSubscriptionRequest;
import com.caestro.server.domain.subscription.dto.response.SubscriptionResponse;
import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionPlan;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.repository.SubscriptionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SubscriptionService subscriptionService;

    private static final Long USER_ID = 1L;

    private User user() {
        return User.builder().oauthProvider("test").oauthId("u").role(User.Role.USER).build();
    }

    @Test
    @DisplayName("구독 생성: 기본 PREMIUM/30일로 저장되고 인증된 사용자로 귀속된다")
    void createSubscription_defaults() {
        given(subscriptionRepository.existsByUserIdAndStatusAndExpiresAtAfter(eq(USER_ID), eq(SubscriptionStatus.ACTIVE), any()))
                .willReturn(false);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
        given(subscriptionRepository.save(any(Subscription.class))).willAnswer(inv -> inv.getArgument(0));

        subscriptionService.createSubscription(USER_ID, new CreateSubscriptionRequest(null, null));

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        Subscription saved = captor.getValue();
        assertThat(saved.getPlan()).isEqualTo(SubscriptionPlan.PREMIUM);
        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(ChronoUnit.DAYS.between(saved.getStartedAt(), saved.getExpiresAt())).isEqualTo(30);
    }

    @Test
    @DisplayName("구독 생성: duration을 주면 해당 일수만큼 만료일이 설정된다")
    void createSubscription_customDuration() {
        given(subscriptionRepository.existsByUserIdAndStatusAndExpiresAtAfter(eq(USER_ID), eq(SubscriptionStatus.ACTIVE), any()))
                .willReturn(false);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
        given(subscriptionRepository.save(any(Subscription.class))).willAnswer(inv -> inv.getArgument(0));

        subscriptionService.createSubscription(USER_ID, new CreateSubscriptionRequest("PREMIUM", 7));

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        assertThat(ChronoUnit.DAYS.between(captor.getValue().getStartedAt(), captor.getValue().getExpiresAt())).isEqualTo(7);
    }

    @Test
    @DisplayName("구독 생성: 이미 활성 구독이 있으면 400(SUBSCRIPTION_ALREADY_ACTIVE)")
    void createSubscription_alreadyActive_throws() {
        given(subscriptionRepository.existsByUserIdAndStatusAndExpiresAtAfter(eq(USER_ID), eq(SubscriptionStatus.ACTIVE), any()))
                .willReturn(true);

        assertThatThrownBy(() -> subscriptionService.createSubscription(USER_ID, new CreateSubscriptionRequest(null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SUBSCRIPTION_ALREADY_ACTIVE);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("구독 생성: 유효하지 않은 plan이면 400(INVALID_SUBSCRIPTION_PLAN)")
    void createSubscription_invalidPlan_throws() {
        assertThatThrownBy(() -> subscriptionService.createSubscription(USER_ID, new CreateSubscriptionRequest("GOLD", null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SUBSCRIPTION_PLAN);
    }

    @Test
    @DisplayName("구독 생성: 게스트(익명) 계정이면 403(GUEST_ACCOUNT_NOT_ALLOWED)")
    void createSubscription_guest_throws() {
        given(subscriptionRepository.existsByUserIdAndStatusAndExpiresAtAfter(eq(USER_ID), eq(SubscriptionStatus.ACTIVE), any()))
                .willReturn(false);
        given(userRepository.findById(USER_ID))
                .willReturn(Optional.of(User.builder().deviceId("dev").role(User.Role.USER).build()));

        assertThatThrownBy(() -> subscriptionService.createSubscription(USER_ID, new CreateSubscriptionRequest(null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GUEST_ACCOUNT_NOT_ALLOWED);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("내 구독 조회: 활성 구독은 active=true로 반환")
    void getMySubscription_active() {
        Subscription sub = Subscription.builder()
                .user(user()).plan(SubscriptionPlan.PREMIUM).status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now().minusDays(1))
                .expiresAt(LocalDateTime.now().plusDays(29))
                .build();
        given(subscriptionRepository.findFirstByUserIdOrderByExpiresAtDesc(USER_ID)).willReturn(Optional.of(sub));

        SubscriptionResponse res = subscriptionService.getMySubscription(USER_ID);

        assertThat(res.active()).isTrue();
        assertThat(res.plan()).isEqualTo(SubscriptionPlan.PREMIUM);
    }

    @Test
    @DisplayName("내 구독 조회: status가 ACTIVE로 남아있어도 만료 시각이 지났으면 active=false로 재계산")
    void getMySubscription_expiredButStatusActive() {
        Subscription sub = Subscription.builder()
                .user(user()).plan(SubscriptionPlan.PREMIUM).status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now().minusDays(31))
                .expiresAt(LocalDateTime.now().minusDays(1)) // 이미 만료
                .build();
        given(subscriptionRepository.findFirstByUserIdOrderByExpiresAtDesc(USER_ID)).willReturn(Optional.of(sub));

        SubscriptionResponse res = subscriptionService.getMySubscription(USER_ID);

        assertThat(res.active()).isFalse();
    }

    @Test
    @DisplayName("내 구독 조회: 구독 이력이 없으면 비활성 응답")
    void getMySubscription_none() {
        given(subscriptionRepository.findFirstByUserIdOrderByExpiresAtDesc(USER_ID)).willReturn(Optional.empty());

        SubscriptionResponse res = subscriptionService.getMySubscription(USER_ID);

        assertThat(res.active()).isFalse();
        assertThat(res.plan()).isNull();
    }

    @Test
    @DisplayName("가드: 활성 구독자는 통과, 비활성자는 403(SUBSCRIPTION_REQUIRED)")
    void requireActivePremium_guard() {
        given(subscriptionRepository.existsByUserIdAndStatusAndExpiresAtAfter(eq(USER_ID), eq(SubscriptionStatus.ACTIVE), any()))
                .willReturn(true);
        subscriptionService.requireActivePremium(USER_ID); // 통과 (예외 없음)

        given(subscriptionRepository.existsByUserIdAndStatusAndExpiresAtAfter(eq(2L), eq(SubscriptionStatus.ACTIVE), any()))
                .willReturn(false);
        assertThatThrownBy(() -> subscriptionService.requireActivePremium(2L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SUBSCRIPTION_REQUIRED);
    }
}
