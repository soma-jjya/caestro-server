package com.caestro.server.domain.subscription.service;

import static com.caestro.server.domain.subscription.appstore.AppStoreFixtures.jws;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.subscription.appstore.AppStoreServerApi;
import com.caestro.server.domain.subscription.appstore.AppStoreSubscription;
import com.caestro.server.domain.subscription.appstore.AppStoreUnavailableException;
import com.caestro.server.domain.subscription.dto.request.AppStorePurchaseRequest;
import com.caestro.server.domain.subscription.dto.response.SubscriptionResponse;
import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionPlan;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import com.caestro.server.domain.subscription.repository.SubscriptionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
class AppStoreSubscriptionServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_ID = 8L;
    private static final String ORIGINAL = "2000000111";
    private static final String LATEST = "2000000999";
    private static final String BUNDLE = "soma.project.caestro";
    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
    private static final ZoneId ZONE = ZoneOffset.UTC;
    /** 앱이 결제에 넣은 거래(서명은 서버가 보지 않는다): 거래 번호만 쓰인다. */
    private static final String SIGNED = jws("{\"transactionId\":\"" + LATEST + "\",\"originalTransactionId\":\"" + ORIGINAL + "\"}");

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AppStoreServerApi appStore;

    private AppStoreSubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new AppStoreSubscriptionService(subscriptionRepository, userRepository, appStore, new ObjectMapper(),
                Clock.fixed(NOW, ZONE), BUNDLE, "peakpic_pro_");
    }

    private static User user(Long id, boolean guest) {
        // 게스트 = 소셜 연동 정보(oauthProvider)가 없는 계정
        User user = User.builder()
                .oauthProvider(guest ? null : "apple")
                .oauthId(guest ? null : "o" + id)
                .role(User.Role.USER)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static AppStoreSubscription purchase(int status, Instant expires, String account, Instant revoked) {
        return new AppStoreSubscription(status, ORIGINAL, LATEST, "peakpic_pro_monthly", BUNDLE,
                NOW.minusSeconds(86_400), expires, revoked, account, true, "Sandbox");
    }

    private static AppStoreSubscription active(String account) {
        return purchase(AppStoreSubscription.STATUS_ACTIVE, NOW.plusSeconds(86_400 * 30), account, null);
    }

    private Subscription row(User owner, SubscriptionStatus status, Instant expires) {
        return Subscription.builder()
                .user(owner).plan(SubscriptionPlan.PREMIUM).status(status)
                .startedAt(LocalDateTime.ofInstant(NOW.minusSeconds(3600), ZONE))
                .expiresAt(LocalDateTime.ofInstant(expires, ZONE))
                .store(SubscriptionStore.APP_STORE).purchaseToken(ORIGINAL)
                .build();
    }

    private void signedIn() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID, false)));
        given(appStore.configured()).willReturn(true);
    }

    private static AppStorePurchaseRequest request() {
        return new AppStorePurchaseRequest(SIGNED, "peakpic_pro_monthly");
    }

    @Test
    @DisplayName("계정 토큰은 앱(iOS ProAccount)과 같은 계산: 사용자 id SHA-256 앞 16바이트의 UUID(버전 4 표시)")
    void appAccountTokenMatchesTheApp() {
        // iOS ProPlansTests 와 같은 값
        assertThat(AppStoreSubscriptionService.appAccountToken(42L)).isEqualTo("73475cb4-0a56-4e8d-a8a0-45ced110137e");
        assertThat(AppStoreSubscriptionService.appAccountToken(42L))
                .isNotEqualTo(AppStoreSubscriptionService.appAccountToken(43L));
    }

    @Test
    @DisplayName("확인: 활성 구독이면 이 계정의 APP_STORE 행이 ACTIVE 로 생기고 만료·요금제·자동 갱신·마지막 거래가 채워진다")
    void verify_active_createsRow() {
        signedIn();
        given(appStore.getSubscription(LATEST))
                .willReturn(Optional.of(active(AppStoreSubscriptionService.appAccountToken(USER_ID))));
        given(subscriptionRepository.findByPurchaseToken(ORIGINAL)).willReturn(Optional.empty());

        SubscriptionResponse response = service.verify(USER_ID, request());

        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(saved.capture());
        Subscription s = saved.getValue();
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.getStore()).isEqualTo(SubscriptionStore.APP_STORE);
        assertThat(s.getPurchaseToken()).isEqualTo(ORIGINAL);
        assertThat(s.getProductId()).isEqualTo("peakpic_pro_monthly");
        assertThat(s.getBasePlanId()).isEqualTo("monthly");
        assertThat(s.getAutoRenewing()).isTrue();
        assertThat(s.getLatestOrderId()).isEqualTo(LATEST);
        assertThat(s.getStoreState()).isEqualTo("ACTIVE");
        assertThat(s.getExpiresAt()).isEqualTo(LocalDateTime.ofInstant(NOW.plusSeconds(86_400 * 30), ZONE));
        assertThat(response.active()).isTrue();
        assertThat(response.store()).isEqualTo(SubscriptionStore.APP_STORE);
        assertThat(response.basePlanId()).isEqualTo("monthly");
    }

    @Test
    @DisplayName("확인: 계정 토큰 없이 산 구독(예전 앱·다른 경로)도 이 계정에 묶는다")
    void verify_withoutAccountToken() {
        signedIn();
        given(appStore.getSubscription(LATEST)).willReturn(Optional.of(active(null)));
        given(subscriptionRepository.findByPurchaseToken(ORIGINAL)).willReturn(Optional.empty());

        assertThat(service.verify(USER_ID, request()).active()).isTrue();
    }

    @Test
    @DisplayName("확인: 게스트 계정은 403, Apple 에 묻지 않는다")
    void verify_guest() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID, true)));

        assertThatThrownBy(() -> service.verify(USER_ID, request()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.GUEST_ACCOUNT_NOT_ALLOWED);
        verify(appStore, never()).getSubscription(anyString());
    }

    @Test
    @DisplayName("확인: 다른 계정 토큰으로 산 구독은 409")
    void verify_otherAccountToken() {
        signedIn();
        given(appStore.getSubscription(LATEST))
                .willReturn(Optional.of(active(AppStoreSubscriptionService.appAccountToken(OTHER_ID))));

        assertThatThrownBy(() -> service.verify(USER_ID, request()))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("확인: 이미 다른 계정에 묶인 구독은 409")
    void verify_ownedByOther() {
        signedIn();
        given(appStore.getSubscription(LATEST)).willReturn(Optional.of(active(null)));
        given(subscriptionRepository.findByPurchaseToken(ORIGINAL))
                .willReturn(Optional.of(row(user(OTHER_ID, false), SubscriptionStatus.ACTIVE, NOW.plusSeconds(60))));

        assertThatThrownBy(() -> service.verify(USER_ID, request()))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER);
    }

    @Test
    @DisplayName("확인: 읽을 수 없는 거래·Apple 이 모르는 거래·다른 앱·다른 상품은 400")
    void verify_invalid() {
        assertThatThrownBy(() -> service.verify(USER_ID, new AppStorePurchaseRequest(SIGNED, "other")))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID);

        signedIn();
        assertThatThrownBy(() -> service.verify(USER_ID, new AppStorePurchaseRequest("garbage", null)))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID);
        assertThatThrownBy(() -> service.verify(USER_ID,
                new AppStorePurchaseRequest(jws("{\"transactionId\":\"../x\"}"), null)))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID);

        given(appStore.getSubscription(LATEST)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.verify(USER_ID, request()))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID);

        given(appStore.getSubscription(LATEST)).willReturn(Optional.of(new AppStoreSubscription(1, ORIGINAL, LATEST,
                "peakpic_pro_monthly", "other.app", NOW, NOW.plusSeconds(60), null, null, true, "Production")));
        assertThatThrownBy(() -> service.verify(USER_ID, request()))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID);
    }

    @Test
    @DisplayName("확인: 인앱 결제 키 미설정·Apple 장애는 503 (앱이 나중에 다시 보낸다)")
    void verify_unavailable() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID, false)));
        given(appStore.configured()).willReturn(false);
        assertThatThrownBy(() -> service.verify(USER_ID, request()))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_VERIFICATION_UNAVAILABLE);

        given(appStore.configured()).willReturn(true);
        given(appStore.getSubscription(LATEST)).willThrow(new AppStoreUnavailableException("down"));
        assertThatThrownBy(() -> service.verify(USER_ID, request()))
                .extracting("errorCode").isEqualTo(ErrorCode.SUBSCRIPTION_VERIFICATION_UNAVAILABLE);
    }

    @Test
    @DisplayName("확인: 만료된 구독은 EXPIRED 로 남고 active=false")
    void verify_expired() {
        signedIn();
        given(appStore.getSubscription(LATEST)).willReturn(Optional.of(
                purchase(AppStoreSubscription.STATUS_EXPIRED, NOW.minusSeconds(60), null, null)));
        given(subscriptionRepository.findByPurchaseToken(ORIGINAL)).willReturn(Optional.empty());

        SubscriptionResponse response = service.verify(USER_ID, request());

        assertThat(response.active()).isFalse();
        assertThat(response.status()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(response.storeState()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("환불·철회는 바로 끝내고(CANCELED), 뒤에 활성 상태가 와도 되살리지 않는다")
    void revokedStaysEnded() {
        signedIn();
        given(appStore.getSubscription(LATEST)).willReturn(Optional.of(purchase(AppStoreSubscription.STATUS_REVOKED,
                NOW.plusSeconds(86_400), null, NOW.minusSeconds(10))));
        given(subscriptionRepository.findByPurchaseToken(ORIGINAL)).willReturn(Optional.empty());

        SubscriptionResponse response = service.verify(USER_ID, request());
        assertThat(response.status()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(response.active()).isFalse();

        Subscription ended = row(user(USER_ID, false), SubscriptionStatus.CANCELED, NOW);
        ended.revoke(LocalDateTime.ofInstant(NOW, ZONE));
        given(subscriptionRepository.findByPurchaseToken(ORIGINAL)).willReturn(Optional.of(ended));
        given(appStore.getSubscription(ORIGINAL)).willReturn(Optional.of(active(null)));
        service.onNotification(notification("DID_RENEW", BUNDLE));
        assertThat(ended.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }

    private static String notification(String type, String bundle) {
        String transaction = jws("{\"originalTransactionId\":\"" + ORIGINAL + "\",\"transactionId\":\"" + LATEST + "\"}");
        return jws("""
                {"notificationType":"%s","notificationUUID":"u1","data":{"bundleId":"%s","environment":"Sandbox",
                 "signedTransactionInfo":"%s"}}
                """.formatted(type, bundle, transaction));
    }

    @Test
    @DisplayName("알림: 묶인 구독이면 Apple 에 다시 물어 고친다 (해지 예약·만료일)")
    void notification_updatesLinkedRow() {
        Subscription existing = row(user(USER_ID, false), SubscriptionStatus.ACTIVE, NOW.plusSeconds(60));
        given(subscriptionRepository.findByPurchaseToken(ORIGINAL)).willReturn(Optional.of(existing));
        AppStoreSubscription renewed = new AppStoreSubscription(1, ORIGINAL, "2000001000", "peakpic_pro_yearly", BUNDLE,
                NOW.minusSeconds(86_400), NOW.plusSeconds(86_400 * 365), null, null, false, "Production");
        given(appStore.getSubscription(ORIGINAL)).willReturn(Optional.of(renewed));

        service.onNotification(notification("DID_CHANGE_RENEWAL_STATUS", BUNDLE));

        verify(subscriptionRepository).save(existing);
        assertThat(existing.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(existing.getBasePlanId()).isEqualTo("yearly");
        assertThat(existing.getAutoRenewing()).isFalse();
        assertThat(existing.getLatestOrderId()).isEqualTo("2000001000");
    }

    @Test
    @DisplayName("알림: 아직 계정에 묶이지 않은 구독·테스트 알림·다른 앱·읽을 수 없는 알림은 아무것도 하지 않는다")
    void notification_ignored() {
        given(subscriptionRepository.findByPurchaseToken(ORIGINAL)).willReturn(Optional.empty());
        service.onNotification(notification("SUBSCRIBED", BUNDLE));
        service.onNotification(jws("{\"notificationType\":\"TEST\",\"data\":{\"bundleId\":\"" + BUNDLE + "\"}}"));
        service.onNotification(notification("DID_RENEW", "other.app"));
        service.onNotification("garbage");

        verify(appStore, never()).getSubscription(anyString());
        verify(subscriptionRepository, never()).save(any());
    }
}
