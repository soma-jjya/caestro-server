package com.caestro.server.domain.subscription.service;

import com.caestro.server.global.resilience.ExternalApiGuard;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.subscription.dto.request.GooglePlayPurchaseRequest;
import com.caestro.server.domain.subscription.dto.response.SubscriptionResponse;
import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionPlan;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import com.caestro.server.domain.subscription.googleplay.GooglePlayPublisher;
import com.caestro.server.domain.subscription.googleplay.GooglePlaySubscription;
import com.caestro.server.domain.subscription.googleplay.GooglePlayUnavailableException;
import com.caestro.server.domain.subscription.repository.SubscriptionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GooglePlaySubscriptionServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_ID = 8L;
    private static final String TOKEN = "token-abc.def_123";
    private static final String PRODUCT = "peakpic_pro";
    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final ZoneId ZONE = ZoneOffset.UTC;

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private GooglePlayPublisher publisher;
    @Mock
    private PlatformTransactionManager transactionManager;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private GooglePlaySubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new GooglePlaySubscriptionService(subscriptionRepository, userRepository, publisher,
                new ExternalApiGuard(CircuitBreakerRegistry.ofDefaults()), transactionManager, meterRegistry,
                Clock.fixed(NOW, ZONE), PRODUCT);
    }

    private static User user(Long id, boolean guest) {
        // 게스트 = 소셜 연동 정보(oauthProvider)가 없는 계정
        User user = User.builder()
                .oauthProvider(guest ? null : "kakao")
                .oauthId(guest ? null : "o" + id)
                .role(User.Role.USER)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static GooglePlaySubscription purchase(String state, Instant expiry, String account, String ack, String linked) {
        return new GooglePlaySubscription(state, ack, "GPA.1", linked, account, NOW.minusSeconds(60), false,
                List.of(new GooglePlaySubscription.LineItem(PRODUCT, "monthly", expiry, true, "GPA.1")));
    }

    private static GooglePlaySubscription active(String account) {
        return purchase(GooglePlaySubscription.STATE_ACTIVE, NOW.plusSeconds(86_400 * 30),
                account, "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED", null);
    }

    private Subscription row(User owner, String token, SubscriptionStatus status, Instant expires) {
        return Subscription.builder()
                .user(owner).plan(SubscriptionPlan.PREMIUM).status(status)
                .startedAt(LocalDateTime.ofInstant(NOW.minusSeconds(3600), ZONE))
                .expiresAt(LocalDateTime.ofInstant(expires, ZONE))
                .store(SubscriptionStore.GOOGLE_PLAY).purchaseToken(token)
                .build();
    }

    private void signedIn() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID, false)));
        given(publisher.configured()).willReturn(true);
    }

    @Test
    @DisplayName("확인: 활성 구매면 이 계정의 GOOGLE_PLAY 행이 ACTIVE 로 생기고 만료·요금제·자동 갱신이 채워진다")
    void verify_active_createsRow() {
        signedIn();
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.empty());
        given(publisher.getSubscription(TOKEN))
                .willReturn(Optional.of(active(GooglePlaySubscriptionService.accountHash(USER_ID))));

        SubscriptionResponse response = service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, PRODUCT));

        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(saved.capture());
        Subscription s = saved.getValue();
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.getStore()).isEqualTo(SubscriptionStore.GOOGLE_PLAY);
        assertThat(s.getPurchaseToken()).isEqualTo(TOKEN);
        assertThat(s.getBasePlanId()).isEqualTo("monthly");
        assertThat(s.getAutoRenewing()).isTrue();
        assertThat(s.getExpiresAt()).isEqualTo(LocalDateTime.ofInstant(NOW.plusSeconds(86_400 * 30), ZONE));
        assertThat(response.active()).isTrue();
        assertThat(response.store()).isEqualTo(SubscriptionStore.GOOGLE_PLAY);
        verify(publisher, never()).acknowledge(anyString(), anyString());
    }

    @Test
    @DisplayName("확인: 승인 대기 구매는 서버도 승인한다")
    void verify_acknowledgesPending() {
        signedIn();
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.empty());
        given(publisher.getSubscription(TOKEN)).willReturn(Optional.of(purchase(GooglePlaySubscription.STATE_ACTIVE,
                NOW.plusSeconds(3600), null, GooglePlaySubscription.ACK_PENDING, null)));

        service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, null));

        verify(publisher).acknowledge(PRODUCT, TOKEN);
    }

    @Test
    @DisplayName("확인: 게스트 계정은 403, Google 에 묻지도 않는다")
    void verify_guestRejected() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID, true)));

        assertThatThrownBy(() -> service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, PRODUCT)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.GUEST_ACCOUNT_NOT_ALLOWED);
        verify(publisher, never()).getSubscription(anyString());
    }

    @Test
    @DisplayName("확인: 다른 계정 해시로 결제된 토큰은 409")
    void verify_otherAccountHash() {
        signedIn();
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.empty());
        given(publisher.getSubscription(TOKEN))
                .willReturn(Optional.of(active(GooglePlaySubscriptionService.accountHash(OTHER_ID))));

        assertThatThrownBy(() -> service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, PRODUCT)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("확인: 이미 다른 계정에 묶인 토큰은 409")
    void verify_tokenOwnedByOther() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID, false)));
        given(subscriptionRepository.findByPurchaseToken(TOKEN))
                .willReturn(Optional.of(row(user(OTHER_ID, false), TOKEN, SubscriptionStatus.ACTIVE, NOW.plusSeconds(60))));

        assertThatThrownBy(() -> service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, PRODUCT)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER);
    }

    @Test
    @DisplayName("확인: Google 이 모르는 토큰·다른 상품은 400")
    void verify_unknownTokenOrProduct() {
        signedIn();
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.empty());
        given(publisher.getSubscription(TOKEN)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, PRODUCT)))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID);
        assertThatThrownBy(() -> service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, "other_product")))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID);
    }

    @Test
    @DisplayName("확인: 키가 없거나 Google 이 응답하지 않으면 503")
    void verify_unavailable() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID, false)));
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.empty());
        given(publisher.configured()).willReturn(false);
        assertThatThrownBy(() -> service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, PRODUCT)))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_VERIFICATION_UNAVAILABLE);

        given(publisher.configured()).willReturn(true);
        given(publisher.getSubscription(TOKEN)).willThrow(new GooglePlayUnavailableException("down"));
        assertThatThrownBy(() -> service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, PRODUCT)))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_VERIFICATION_UNAVAILABLE);
    }

    @Test
    @DisplayName("확인: 결제 대기(PENDING)는 행을 남기되 이용할 수 없다")
    void verify_pending() {
        signedIn();
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.empty());
        given(publisher.getSubscription(TOKEN)).willReturn(Optional.of(purchase(
                GooglePlaySubscription.STATE_PENDING, null, null, GooglePlaySubscription.ACK_PENDING, null)));

        SubscriptionResponse response = service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, PRODUCT));

        assertThat(response.active()).isFalse();
        assertThat(response.storeState()).isEqualTo(GooglePlaySubscription.STATE_PENDING);
        verify(publisher, never()).acknowledge(anyString(), anyString());
    }

    @Test
    @DisplayName("확인: 해지 예약(CANCELED)이어도 만료 전이면 이용 가능, 자동 갱신은 꺼짐")
    void verify_canceledStillEntitled() {
        signedIn();
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.empty());
        GooglePlaySubscription canceled = new GooglePlaySubscription(GooglePlaySubscription.STATE_CANCELED,
                "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED", "GPA.1", null, null, NOW, false,
                List.of(new GooglePlaySubscription.LineItem(PRODUCT, "yearly", NOW.plusSeconds(600), false, null)));
        given(publisher.getSubscription(TOKEN)).willReturn(Optional.of(canceled));

        SubscriptionResponse response = service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, PRODUCT));

        assertThat(response.active()).isTrue();
        assertThat(response.autoRenewing()).isFalse();
    }

    @Test
    @DisplayName("알림: 아는 토큰의 갱신은 만료일을 늘린다")
    void notification_renewal() {
        Subscription existing = row(user(USER_ID, false), TOKEN, SubscriptionStatus.ACTIVE, NOW.plusSeconds(60));
        given(publisher.getSubscription(TOKEN)).willReturn(Optional.of(active(null)));
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.of(existing));

        service.onSubscriptionNotification(TOKEN);

        assertThat(existing.getExpiresAt()).isEqualTo(LocalDateTime.ofInstant(NOW.plusSeconds(86_400 * 30), ZONE));
        assertThat(existing.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(subscriptionRepository).save(existing);
    }

    @Test
    @DisplayName("알림: 만료되면 EXPIRED")
    void notification_expired() {
        Subscription existing = row(user(USER_ID, false), TOKEN, SubscriptionStatus.ACTIVE, NOW.plusSeconds(60));
        given(publisher.getSubscription(TOKEN)).willReturn(Optional.of(purchase(
                "SUBSCRIPTION_STATE_EXPIRED", NOW.minusSeconds(60), null, "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED", null)));
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.of(existing));

        service.onSubscriptionNotification(TOKEN);

        assertThat(existing.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
    }

    @Test
    @DisplayName("알림: 요금제 변경으로 생긴 새 토큰은 이전 토큰의 계정으로 이어지고 이전 행은 끝난다")
    void notification_upgradeCarriesOver() {
        String newToken = "token-new";
        Subscription previous = row(user(USER_ID, false), TOKEN, SubscriptionStatus.ACTIVE, NOW.plusSeconds(86_400));
        given(publisher.getSubscription(newToken)).willReturn(Optional.of(purchase(GooglePlaySubscription.STATE_ACTIVE,
                NOW.plusSeconds(86_400 * 365), null, "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED", TOKEN)));
        given(subscriptionRepository.findByPurchaseToken(newToken)).willReturn(Optional.empty());
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.of(previous));

        service.onSubscriptionNotification(newToken);

        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        Subscription created = saved.getAllValues().get(0);
        assertThat(created.getPurchaseToken()).isEqualTo(newToken);
        assertThat(created.getUser().getId()).isEqualTo(USER_ID);
        assertThat(created.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(previous.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(previous.getStoreState()).isEqualTo("REPLACED");
    }

    @Test
    @DisplayName("알림: 앱이 아직 보내지 않은 토큰은 기다린다(아무것도 저장하지 않음)")
    void notification_unknownTokenWaits() {
        given(publisher.getSubscription(TOKEN)).willReturn(Optional.of(active(null)));
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.empty());

        service.onSubscriptionNotification(TOKEN);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("알림: 환불(voided)은 바로 끝내고, 그 뒤 활성 알림이 와도 되살리지 않는다")
    void voided_revokesAndSticks() {
        Subscription existing = row(user(USER_ID, false), TOKEN, SubscriptionStatus.ACTIVE, NOW.plusSeconds(86_400));
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.of(existing));

        service.onVoidedPurchase(TOKEN);
        assertThat(existing.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(existing.getExpiresAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZONE));

        given(publisher.getSubscription(TOKEN)).willReturn(Optional.of(active(null)));
        service.onSubscriptionNotification(TOKEN);
        assertThat(existing.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }

    @Test
    @DisplayName("계정 해시: 사용자 id 문자열의 SHA-256 hex (앱과 같은 계산)")
    void accountHash_matchesApp() {
        // printf '7' | shasum -a 256
        assertThat(GooglePlaySubscriptionService.accountHash(7L))
                .isEqualTo("7902699be42c8a8e46fbbb4501726517e86b22c56a189f7625a6da49081b2451");
    }

    @Test
    @DisplayName("지표: 확인 결과가 subscription.verify{store,result} 로 집계된다 (#184)")
    void metrics_countVerifyResults() {
        signedIn();
        given(subscriptionRepository.findByPurchaseToken(TOKEN)).willReturn(Optional.empty());
        given(publisher.getSubscription(TOKEN)).willReturn(Optional.of(active(null)));

        service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, PRODUCT));
        assertThatThrownBy(() -> service.verify(USER_ID, new GooglePlayPurchaseRequest(TOKEN, "other_product")));

        assertThat(meterRegistry.counter("subscription.verify", "store", "google_play", "result", "ok").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("subscription.verify", "store", "google_play", "result", "invalid").count()).isEqualTo(1);
    }
}
