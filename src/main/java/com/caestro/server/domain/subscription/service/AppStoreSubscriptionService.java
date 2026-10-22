package com.caestro.server.domain.subscription.service;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * App Store 구독을 계정에 묶고 최신 상태로 유지한다 (contracts/subscription-app-store.md).
 * Google Play 쪽(GooglePlaySubscriptionService)과 같은 행·같은 권한 기준을 쓴다.
 *
 * <ul>
 *   <li>앱이 결제 직후 서명된 거래(JWS)를 보내면, 거래 번호만 꺼내 Apple 에 지금 상태를 물어 확인하고 그 계정의 구독 행을 만들거나 고친다.</li>
 *   <li>갱신·해지·환불은 App Store Server Notifications V2 로 들어오고, 알림 내용은 믿지 않고 거래 번호로 Apple 에 다시 묻는다.</li>
 * </ul>
 * 행의 purchaseToken 은 originalTransactionId — 요금제를 바꾸거나 갱신해도 같은, 구독 한 줄의 번호다.
 */
@Slf4j
@Service
public class AppStoreSubscriptionService {

    static final String STATE_REVOKED = "REVOKED";

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final AppStoreServerApi appStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String bundleId;
    private final String productPrefix;

    public AppStoreSubscriptionService(SubscriptionRepository subscriptionRepository,
                                       UserRepository userRepository,
                                       AppStoreServerApi appStore,
                                       ObjectMapper objectMapper,
                                       Clock clock,
                                       @Value("${apple.app-store.bundle-id:soma.project.caestro}") String bundleId,
                                       @Value("${apple.app-store.product-prefix:peakpic_pro_}") String productPrefix) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.appStore = appStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.bundleId = bundleId;
        this.productPrefix = productPrefix;
    }

    /**
     * 앱이 보낸 거래를 확인해 이 계정의 구독으로 기록한다. 같은 구독을 다시 보내면 최신 상태로 고칠 뿐이다(앱 시작·구매 복원).
     *
     * @throws CustomException GUEST_ACCOUNT_NOT_ALLOWED(403) · SUBSCRIPTION_PURCHASE_INVALID(400)
     *                         · SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER(409) · SUBSCRIPTION_VERIFICATION_UNAVAILABLE(503)
     */
    @Transactional
    public SubscriptionResponse verify(Long userId, AppStorePurchaseRequest request) {
        if (request.productId() != null && !request.productId().startsWith(productPrefix)) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        // 게스트 계정은 기기에 묶여 있어 결제를 다른 기기로 옮길 수 없다: 소셜 로그인이 먼저다.
        // (iOS 는 게스트도 살 수 있고 이 기기에서는 App Store 가 Pro 를 지킨다. 로그인 뒤 앱이 다시 보내면 그때 묶인다)
        if (user.isGuest()) {
            throw new CustomException(ErrorCode.GUEST_ACCOUNT_NOT_ALLOWED);
        }
        // 앱이 보낸 JWS 는 서명을 확인하지 않는다: 거래 번호만 꺼내고, 상태는 Apple 에 직접 묻는다
        String transactionId = transactionIdOf(request.signedTransaction())
                .orElseThrow(() -> new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID));

        AppStoreSubscription purchase = fetch(transactionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID));
        if (purchase.bundleId() != null && !bundleId.equals(purchase.bundleId())) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID);
        }
        // 앱은 결제할 때 계정 id 해시로 만든 UUID 를 넣는다. 다른 계정으로 산 구독을 이 계정에 붙이지 못하게 한다
        String owner = purchase.appAccountToken();
        if (owner != null && !owner.equalsIgnoreCase(appAccountToken(userId))) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER);
        }
        String key = purchase.originalTransactionId();
        Optional<Subscription> existing = subscriptionRepository.findByPurchaseToken(key);
        if (existing.isPresent() && !existing.get().getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER);
        }

        Subscription subscription = existing.orElseGet(() -> newRow(user, key));
        apply(subscription, purchase);
        subscriptionRepository.save(subscription);
        log.info("App Store subscription verified: user={} state={} product={} env={}",
                userId, purchase.stateName(), purchase.productId(), purchase.environment());
        return SubscriptionResponse.from(subscription, isActive(subscription));
    }

    /**
     * App Store Server Notifications V2: 알림 속 거래의 구독 상태를 Apple 에 다시 물어 반영한다.
     * 아직 어느 계정에도 묶이지 않은 구독이면(앱이 확인을 보내기 전) 아무것도 하지 않는다 — 앱의 확인 요청이 계정에 묶는다.
     *
     * @throws AppStoreUnavailableException Apple 에 물을 수 없을 때 (알림이 다시 오도록 호출자가 실패로 답한다)
     */
    @Transactional
    public void onNotification(String signedPayload) {
        JsonNode notification = payload(signedPayload);
        if (notification == null) {
            log.warn("App Store notification unreadable; ignored");
            return;
        }
        String type = notification.path("notificationType").asText();
        JsonNode data = notification.path("data");
        if ("TEST".equals(type)) {
            log.info("App Store test notification received");
            return;
        }
        if (data.hasNonNull("bundleId") && !bundleId.equals(data.path("bundleId").asText())) {
            log.warn("App Store notification for another app: {}", data.path("bundleId").asText());
            return;
        }
        JsonNode transaction = payload(data.path("signedTransactionInfo").asText(null));
        if (transaction == null || !transaction.hasNonNull("originalTransactionId")) {
            log.info("App Store notification {} without a transaction; ignored", type);
            return;
        }
        String key = transaction.path("originalTransactionId").asText();
        Optional<Subscription> row = subscriptionRepository.findByPurchaseToken(key);
        if (row.isEmpty()) {
            log.info("App Store notification {} for a subscription not linked to any account yet; waiting for the app", type);
            return;
        }
        Optional<AppStoreSubscription> fetched = appStore.getSubscription(key);
        if (fetched.isEmpty()) {
            log.info("App Store notification {} for a transaction Apple does not know; ignored", type);
            return;
        }
        Subscription subscription = row.get();
        apply(subscription, fetched.get());
        subscriptionRepository.save(subscription);
        log.info("App Store notification {}: subscription={} state={}", type, subscription.getId(), fetched.get().stateName());
    }

    /**
     * 앱이 결제에 넣는 계정 식별자: 사용자 id(문자열)의 SHA-256 앞 16바이트를 UUID(버전 4 표시)로.
     * 앱(ProAccount.appAccountToken)과 같은 계산이고, 해시는 Google Play 의 obfuscatedAccountId 와 같다.
     */
    static String appAccountToken(Long userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0F) | 0x40);
            digest[8] = (byte) ((digest[8] & 0x3F) | 0x80);
            long high = 0;
            long low = 0;
            for (int i = 0; i < 8; i++) {
                high = (high << 8) | (digest[i] & 0xFF);
                low = (low << 8) | (digest[i + 8] & 0xFF);
            }
            return new UUID(high, low).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<String> transactionIdOf(String signedTransaction) {
        JsonNode transaction = payload(signedTransaction);
        if (transaction == null) {
            return Optional.empty();
        }
        String id = transaction.hasNonNull("transactionId")
                ? transaction.path("transactionId").asText()
                : transaction.path("originalTransactionId").asText(null);
        // 숫자 거래 번호만 Apple 경로에 넣는다
        return Optional.ofNullable(id).filter(value -> value.matches("[0-9]{1,32}"));
    }

    private JsonNode payload(String jws) {
        return AppStoreSubscription.AppStoreJws.payload(jws, objectMapper);
    }

    private Optional<AppStoreSubscription> fetch(String transactionId) {
        if (!appStore.configured()) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_VERIFICATION_UNAVAILABLE);
        }
        try {
            return appStore.getSubscription(transactionId);
        } catch (AppStoreUnavailableException e) {
            log.warn("App Store verification unavailable: {}", e.getMessage());
            throw new CustomException(ErrorCode.SUBSCRIPTION_VERIFICATION_UNAVAILABLE);
        }
    }

    private Subscription newRow(User user, String originalTransactionId) {
        LocalDateTime now = now();
        return Subscription.builder()
                .user(user)
                .plan(SubscriptionPlan.PREMIUM)
                .status(SubscriptionStatus.EXPIRED)
                .startedAt(now)
                .expiresAt(now)
                .store(SubscriptionStore.APP_STORE)
                .purchaseToken(originalTransactionId)
                .build();
    }

    /** Apple 이 알려 준 상태를 권한 기준(status · expiresAt)으로 줄여 덮어쓴다. 환불로 끝난 구독은 되살리지 않는다. */
    private void apply(Subscription subscription, AppStoreSubscription purchase) {
        if (STATE_REVOKED.equals(subscription.getStoreState())) {
            return;
        }
        if (purchase.revoked()) {
            subscription.revoke(now());
            return;
        }
        LocalDateTime now = now();
        LocalDateTime expiresAt = purchase.expiresDate() != null ? toLocal(purchase.expiresDate()) : now;
        SubscriptionStatus status = purchase.entitledState() && expiresAt.isAfter(now)
                ? SubscriptionStatus.ACTIVE
                : SubscriptionStatus.EXPIRED;
        subscription.applyStoreState(
                status,
                purchase.stateName(),
                purchase.productId(),
                basePlanOf(purchase.productId()),
                purchase.originalPurchaseDate() != null ? toLocal(purchase.originalPurchaseDate()) : null,
                expiresAt,
                purchase.autoRenewing(),
                purchase.transactionId()
        );
    }

    /** peakpic_pro_monthly → monthly: Google Play 의 기본 요금제 ID 와 같은 값. */
    private String basePlanOf(String productId) {
        return productId != null && productId.startsWith(productPrefix)
                ? productId.substring(productPrefix.length())
                : null;
    }

    private boolean isActive(Subscription subscription) {
        return subscription.getStatus() == SubscriptionStatus.ACTIVE && subscription.getExpiresAt().isAfter(now());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
