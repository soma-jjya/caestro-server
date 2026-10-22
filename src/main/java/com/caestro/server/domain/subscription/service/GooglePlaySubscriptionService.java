package com.caestro.server.domain.subscription.service;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Google Play 구독을 계정에 묶고 최신 상태로 유지한다 (contracts/subscription-google-play.md).
 *
 * <ul>
 *   <li>앱이 결제 직후 구매 토큰을 보내면 Google 에 상태를 물어 확인하고 그 계정의 구독 행을 만들거나 고친다.</li>
 *   <li>갱신·해지·환불은 Google 실시간 알림(RTDN)으로 들어오고, 알림 내용은 믿지 않고 토큰으로 Google 에 다시 묻는다.</li>
 * </ul>
 * 권한 판단은 기존과 같다: status=ACTIVE 이고 expiresAt 이 지금 이후 (SubscriptionService#isActivePremium).
 */
@Slf4j
@Service
public class GooglePlaySubscriptionService {

    static final String STATE_REVOKED = "REVOKED";

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final GooglePlayPublisher publisher;
    private final Clock clock;
    private final String productId;

    public GooglePlaySubscriptionService(SubscriptionRepository subscriptionRepository,
                                         UserRepository userRepository,
                                         GooglePlayPublisher publisher,
                                         Clock clock,
                                         @Value("${google.play.product-id:peakpic_pro}") String productId) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.publisher = publisher;
        this.clock = clock;
        this.productId = productId;
    }

    /**
     * 앱이 보낸 구매 토큰을 확인해 이 계정의 구독으로 기록한다. 같은 토큰을 다시 보내면 최신 상태로 고칠 뿐이다(구매 복원).
     *
     * @throws CustomException GUEST_ACCOUNT_NOT_ALLOWED(403) · SUBSCRIPTION_PURCHASE_INVALID(400)
     *                         · SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER(409) · SUBSCRIPTION_VERIFICATION_UNAVAILABLE(503)
     */
    @Transactional
    public SubscriptionResponse verify(Long userId, GooglePlayPurchaseRequest request) {
        if (request.productId() != null && !productId.equals(request.productId())) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        // 게스트 계정은 기기에 묶여 있어 결제를 다른 기기로 옮길 수 없다: 소셜 로그인이 먼저다
        if (user.isGuest()) {
            throw new CustomException(ErrorCode.GUEST_ACCOUNT_NOT_ALLOWED);
        }
        String token = request.purchaseToken();
        Optional<Subscription> existing = subscriptionRepository.findByPurchaseToken(token);
        if (existing.isPresent() && !existing.get().getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER);
        }

        GooglePlaySubscription purchase = fetch(token)
                .orElseThrow(() -> new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID));
        GooglePlaySubscription.LineItem line = purchase.lineFor(productId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID));
        // 앱은 결제할 때 계정 id 의 해시를 넣는다. 다른 계정으로 산 토큰을 이 계정에 붙이지 못하게 한다
        String owner = purchase.obfuscatedAccountId();
        if (owner != null && !owner.equals(accountHash(userId))) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER);
        }

        Subscription subscription = existing.orElseGet(() -> newRow(user, token));
        apply(subscription, purchase, line);
        subscriptionRepository.save(subscription);
        replaceLinked(purchase, userId);
        acknowledgeIfNeeded(purchase, token);
        log.info("Google Play subscription verified: user={} state={} plan={} test={}",
                userId, purchase.subscriptionState(), line.basePlanId(), purchase.testPurchase());
        return SubscriptionResponse.from(subscription, isActive(subscription));
    }

    /**
     * 실시간 알림(구독 구매·갱신·해지·보류·만료 등): 토큰의 지금 상태를 Google 에 다시 물어 반영한다.
     * 모르는 토큰이면, 요금제 변경처럼 이전 토큰(linkedPurchaseToken)을 아는 경우에만 그 계정으로 이어 붙인다.
     *
     * @throws GooglePlayUnavailableException Google 에 물을 수 없을 때 (알림이 다시 오도록 호출자가 실패로 답한다)
     */
    @Transactional
    public void onSubscriptionNotification(String token) {
        Optional<GooglePlaySubscription> fetched = publisher.getSubscription(token);
        if (fetched.isEmpty()) {
            log.info("RTDN for a token Google does not know; ignored");
            return;
        }
        GooglePlaySubscription purchase = fetched.get();
        Optional<GooglePlaySubscription.LineItem> line = purchase.lineFor(productId);
        if (line.isEmpty()) {
            return;  // 다른 구독 상품
        }
        Optional<Subscription> row = subscriptionRepository.findByPurchaseToken(token);
        if (row.isEmpty() && purchase.linkedPurchaseToken() != null) {
            row = subscriptionRepository.findByPurchaseToken(purchase.linkedPurchaseToken())
                    .map(previous -> newRow(previous.getUser(), token));
        }
        if (row.isEmpty()) {
            // 앱이 아직 토큰을 보내지 않았다: 결제 직후 앱의 확인 요청이 계정에 묶는다
            log.info("RTDN for a token not linked to any account yet ({}); waiting for the app", purchase.subscriptionState());
            return;
        }
        Subscription subscription = row.get();
        apply(subscription, purchase, line.get());
        subscriptionRepository.save(subscription);
        replaceLinked(purchase, subscription.getUser().getId());
        acknowledgeIfNeeded(purchase, token);
    }

    /**
     * 실시간 알림(무효화된 구매: 환불·지불 거절): 남은 기간과 관계없이 바로 끝낸다.
     */
    @Transactional
    public void onVoidedPurchase(String token) {
        subscriptionRepository.findByPurchaseToken(token).ifPresent(subscription -> {
            subscription.revoke(now());
            subscriptionRepository.save(subscription);
            log.info("Google Play purchase voided: subscription={} user={}", subscription.getId(), subscription.getUser().getId());
        });
    }

    /** 앱이 결제에 넣는 계정 식별자: 사용자 id(문자열)의 SHA-256 hex. 앱(ProRepository.accountHash)과 같은 계산이다. */
    static String accountHash(Long userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<GooglePlaySubscription> fetch(String token) {
        if (!publisher.configured()) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_VERIFICATION_UNAVAILABLE);
        }
        try {
            return publisher.getSubscription(token);
        } catch (GooglePlayUnavailableException e) {
            log.warn("Google Play verification unavailable: {}", e.getMessage());
            throw new CustomException(ErrorCode.SUBSCRIPTION_VERIFICATION_UNAVAILABLE);
        }
    }

    private Subscription newRow(User user, String token) {
        LocalDateTime now = now();
        return Subscription.builder()
                .user(user)
                .plan(SubscriptionPlan.PREMIUM)
                .status(SubscriptionStatus.EXPIRED)
                .startedAt(now)
                .expiresAt(now)
                .store(SubscriptionStore.GOOGLE_PLAY)
                .purchaseToken(token)
                .build();
    }

    /** Google 이 알려 준 상태를 권한 기준(status · expiresAt)으로 줄여 덮어쓴다. 환불로 끝난 구매는 되살리지 않는다. */
    private void apply(Subscription subscription, GooglePlaySubscription purchase, GooglePlaySubscription.LineItem line) {
        if (STATE_REVOKED.equals(subscription.getStoreState())) {
            return;
        }
        LocalDateTime now = now();
        LocalDateTime expiresAt = line.expiryTime() != null ? toLocal(line.expiryTime()) : now;
        SubscriptionStatus status = purchase.entitledState() && expiresAt.isAfter(now)
                ? SubscriptionStatus.ACTIVE
                : SubscriptionStatus.EXPIRED;
        subscription.applyStoreState(
                status,
                purchase.subscriptionState(),
                line.productId(),
                line.basePlanId(),
                purchase.startTime() != null ? toLocal(purchase.startTime()) : null,
                expiresAt,
                line.autoRenewEnabled(),
                purchase.latestOrderId()
        );
    }

    /** 요금제 변경·재구독: 새 토큰이 이전 토큰을 대신한다. 같은 계정의 이전 행만 끝낸다. */
    private void replaceLinked(GooglePlaySubscription purchase, Long userId) {
        String linked = purchase.linkedPurchaseToken();
        if (linked == null) {
            return;
        }
        subscriptionRepository.findByPurchaseToken(linked)
                .filter(previous -> previous.getUser().getId().equals(userId))
                .ifPresent(previous -> {
                    previous.markReplaced(now());
                    subscriptionRepository.save(previous);
                });
    }

    private void acknowledgeIfNeeded(GooglePlaySubscription purchase, String token) {
        if (purchase.entitledState() && purchase.needsAcknowledgement()) {
            publisher.acknowledge(productId, token);
        }
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
