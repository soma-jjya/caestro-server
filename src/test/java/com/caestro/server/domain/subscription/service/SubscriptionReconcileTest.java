package com.caestro.server.domain.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.subscription.appstore.AppStoreServerApi;
import com.caestro.server.domain.subscription.appstore.AppStoreUnavailableException;
import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionPlan;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import com.caestro.server.domain.subscription.googleplay.GooglePlayPublisher;
import com.caestro.server.domain.subscription.googleplay.GooglePlaySubscription;
import com.caestro.server.domain.subscription.repository.SubscriptionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #185 — 알림 없이도 만료 임박·최근 만료 구독이 스토어 상태로 맞춰지고, 한 행의 스토어 장애가 다른 행을 막지 않는다.
 * 실제 MySQL 필요 (docker-compose up -d / CI 서비스 컨테이너). 스토어는 목.
 */
// RANDOM_PORT: 모의 서블릿 환경에는 WS ServerContainer가 없어 WS 설정 빈이 초기화되지 못한다 (contextLoads와 동일)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubscriptionReconcileTest {

    private static final String PRODUCT = "peakpic_pro";

    @Autowired private SubscriptionReconcileScheduler scheduler;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private UserRepository userRepository;

    @MockitoBean private GooglePlayPublisher publisher;
    @MockitoBean private AppStoreServerApi appStore;

    private User user;
    private final List<String> tokens = new ArrayList<>();
    private String expiringSoon;   // Google, ACTIVE, 1시간 뒤 만료 → 갱신 알림을 놓친 상황
    private String recentlyExpired; // Google, 어제 만료 → 이미 Pro 가 풀린 사용자
    private String appleBroken;     // Apple, 1시간 뒤 만료 → Apple 이 응답하지 않는 상황
    private String farAway;         // Google, 10일 뒤 만료 → 대상 아님

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        user = userRepository.save(User.builder()
                .oauthProvider("test").oauthId("rc-" + suffix).nickname("대사").role(User.Role.USER).build());
        LocalDateTime now = LocalDateTime.now();
        expiringSoon = save(SubscriptionStore.GOOGLE_PLAY, "rc-soon-" + suffix, SubscriptionStatus.ACTIVE, now.plusHours(1), "SUBSCRIPTION_STATE_ACTIVE");
        recentlyExpired = save(SubscriptionStore.GOOGLE_PLAY, "rc-expired-" + suffix, SubscriptionStatus.EXPIRED, now.minusDays(1), "SUBSCRIPTION_STATE_EXPIRED");
        appleBroken = save(SubscriptionStore.APP_STORE, "rc-apple-" + suffix, SubscriptionStatus.ACTIVE, now.plusHours(1), "ACTIVE");
        farAway = save(SubscriptionStore.GOOGLE_PLAY, "rc-far-" + suffix, SubscriptionStatus.ACTIVE, now.plusDays(10), "SUBSCRIPTION_STATE_ACTIVE");

        given(publisher.configured()).willReturn(true);
        given(appStore.configured()).willReturn(true);
        given(publisher.getSubscription(expiringSoon)).willReturn(Optional.of(renewedFor30Days()));
        given(publisher.getSubscription(recentlyExpired)).willReturn(Optional.of(renewedFor30Days()));
        given(appStore.getSubscription(appleBroken)).willThrow(new AppStoreUnavailableException("down"));
    }

    @AfterEach
    void cleanup() {
        tokens.forEach(token -> subscriptionRepository.findByPurchaseToken(token).ifPresent(subscriptionRepository::delete));
        userRepository.delete(user);
    }

    @Test
    @DisplayName("알림 없이도 만료 임박·최근 만료 행이 스토어 상태로 늘어나고, Apple 장애 행 하나는 실패로 집계될 뿐 나머지를 막지 않는다")
    void reconcile_catchesUpMissedRenewals_andIsolatesFailures() {
        SubscriptionReconcileScheduler.Result result = scheduler.runOnce(LocalDateTime.now());

        assertThat(result.changed()).as("놓친 갱신 2건").isGreaterThanOrEqualTo(2);
        assertThat(result.failed()).as("Apple 장애 1건").isGreaterThanOrEqualTo(1);

        Subscription soon = subscriptionRepository.findByPurchaseToken(expiringSoon).orElseThrow();
        assertThat(soon.getExpiresAt()).as("만료 임박 행은 30일 연장").isAfter(LocalDateTime.now().plusDays(29));
        Subscription expired = subscriptionRepository.findByPurchaseToken(recentlyExpired).orElseThrow();
        assertThat(expired.getStatus()).as("이미 풀린 사용자가 되살아남").isEqualTo(SubscriptionStatus.ACTIVE);
        Subscription apple = subscriptionRepository.findByPurchaseToken(appleBroken).orElseThrow();
        assertThat(apple.getExpiresAt()).as("Apple 장애 행은 그대로").isBefore(LocalDateTime.now().plusHours(2));
        verify(publisher, never()).getSubscription(farAway);
    }

    private String save(SubscriptionStore store, String token, SubscriptionStatus status, LocalDateTime expiresAt, String storeState) {
        subscriptionRepository.save(Subscription.builder()
                .user(user).plan(SubscriptionPlan.PREMIUM).status(status)
                .startedAt(LocalDateTime.now().minusDays(30)).expiresAt(expiresAt)
                .store(store).purchaseToken(token).productId(PRODUCT).basePlanId("monthly")
                .storeState(storeState).autoRenewing(true)
                .build());
        tokens.add(token);
        return token;
    }

    private static GooglePlaySubscription renewedFor30Days() {
        Instant now = Instant.now();
        return new GooglePlaySubscription(GooglePlaySubscription.STATE_ACTIVE, "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                "GPA.renewed", null, null, now.minusSeconds(86_400 * 30), false,
                List.of(new GooglePlaySubscription.LineItem(PRODUCT, "monthly", now.plusSeconds(86_400 * 30), true, "GPA.renewed")));
    }
}
