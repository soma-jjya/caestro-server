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
import com.caestro.server.global.resilience.ExternalApiGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * App Store 구독을 계정에 묶고 최신 상태로 유지한다 (contracts/subscription-app-store.md).
 * Google Play 쪽(GooglePlaySubscriptionService)과 같은 행·같은 권한 기준을 쓴다.
 *
 * <ul>
 *   <li>앱이 결제 직후 서명된 거래(JWS)를 보내면, 거래 번호만 꺼내 Apple 에 지금 상태를 물어 확인하고 그 계정의 구독 행을 만들거나 고친다.</li>
 *   <li>갱신·해지·환불은 App Store Server Notifications V2 로 들어오고, 알림 내용은 믿지 않고 거래 번호로 Apple 에 다시 묻는다.</li>
 * </ul>
 * 행의 purchaseToken 은 originalTransactionId — 요금제를 바꾸거나 갱신해도 같은, 구독 한 줄의 번호다.
 *
 * <p>처리 순서는 세 토막이다(#184): 읽기 트랜잭션(자체 검사) → 트랜잭션 없이 Apple 조회 → 짧은 쓰기 트랜잭션(반영).
 * Apple 응답을 기다리는 동안 DB 커넥션을 쥐지 않기 위해서다. Apple 조회는 서킷브레이커 뒤에 있어 장애 때 즉시 503 이 된다.
 */
@Slf4j
@Service
public class AppStoreSubscriptionService {

    static final String STATE_REVOKED = "REVOKED";
    static final String CIRCUIT = "app-store";
    static final String STORE_TAG = "app_store";

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final AppStoreServerApi appStore;
    private final ExternalApiGuard guard;
    private final SubscriptionMetrics metrics;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate write;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String bundleId;
    private final String productPrefix;

    public AppStoreSubscriptionService(SubscriptionRepository subscriptionRepository,
                                       UserRepository userRepository,
                                       AppStoreServerApi appStore,
                                       ExternalApiGuard guard,
                                       PlatformTransactionManager transactionManager,
                                       MeterRegistry meterRegistry,
                                       ObjectMapper objectMapper,
                                       Clock clock,
                                       @Value("${apple.app-store.bundle-id:soma.project.caestro}") String bundleId,
                                       @Value("${apple.app-store.product-prefix:peakpic_pro_}") String productPrefix) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.appStore = appStore;
        this.guard = guard;
        this.metrics = new SubscriptionMetrics(meterRegistry, STORE_TAG);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.write = new TransactionTemplate(transactionManager);
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
    public SubscriptionResponse verify(Long userId, AppStorePurchaseRequest request) {
        try {
            SubscriptionResponse response = doVerify(userId, request);
            metrics.verified("ok");
            return response;
        } catch (CustomException e) {
            metrics.verified(SubscriptionMetrics.resultOf(e.getErrorCode()));
            throw e;
        }
    }

    private SubscriptionResponse doVerify(Long userId, AppStorePurchaseRequest request) {
        if (request.productId() != null && !request.productId().startsWith(productPrefix)) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID);
        }

        // 1. 읽기 트랜잭션: 계정 검사. Apple 을 부르기 전에 끝낼 수 있는 거절은 여기서 끝낸다
        User user = readOnly.execute(status -> {
            User found = userRepository.findById(userId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
            // 게스트 계정은 기기에 묶여 있어 결제를 다른 기기로 옮길 수 없다: 소셜 로그인이 먼저다.
            // (iOS 는 게스트도 살 수 있고 이 기기에서는 App Store 가 Pro 를 지킨다. 로그인 뒤 앱이 다시 보내면 그때 묶인다)
            if (found.isGuest()) {
                throw new CustomException(ErrorCode.GUEST_ACCOUNT_NOT_ALLOWED);
            }
            return found;
        });
        // 앱이 보낸 JWS 는 서명을 확인하지 않는다: 거래 번호만 꺼내고, 상태는 Apple 에 직접 묻는다
        String transactionId = transactionIdOf(request.signedTransaction())
                .orElseThrow(() -> new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_INVALID));

        // 2. 트랜잭션 없이 Apple 에 묻는다 — 응답을 기다리는 동안 DB 커넥션을 쥐지 않는다
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

        // 3. 짧은 쓰기 트랜잭션: 행을 찾거나 만들어 Apple 상태를 덮어쓴다. 다른 계정에 묶인 구독이면 409
        Subscription subscription = writeRetryingOnConflict(() -> {
            Subscription row = subscriptionRepository.findByPurchaseToken(key).orElseGet(() -> newRow(user, key));
            if (!row.getUser().getId().equals(userId)) {
                throw new CustomException(ErrorCode.SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER);
            }
            apply(row, purchase);
            subscriptionRepository.save(row);
            subscriptionRepository.flush();   // 같은 구독의 동시 INSERT 는 여기서 유니크에 걸린다 → 다시 읽어 그 행에 반영
            return row;
        });
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
    public void onNotification(String signedPayload) {
        JsonNode notification = payload(signedPayload);
        if (notification == null) {
            log.warn("App Store notification unreadable; ignored");
            metrics.notified("ignored");
            return;
        }
        String type = notification.path("notificationType").asText();
        JsonNode data = notification.path("data");
        if ("TEST".equals(type)) {
            log.info("App Store test notification received");
            metrics.notified("test");
            return;
        }
        if (data.hasNonNull("bundleId") && !bundleId.equals(data.path("bundleId").asText())) {
            log.warn("App Store notification for another app: {}", data.path("bundleId").asText());
            metrics.notified("ignored");
            return;
        }
        JsonNode transaction = payload(data.path("signedTransactionInfo").asText(null));
        if (transaction == null || !transaction.hasNonNull("originalTransactionId")) {
            log.info("App Store notification {} without a transaction; ignored", type);
            metrics.notified("ignored");
            return;
        }
        String key = transaction.path("originalTransactionId").asText();
        try {
            SubscriptionRefresh result = refresh(key);
            log.info("App Store notification {}: {}", type, result);
            metrics.notified(result == SubscriptionRefresh.SKIPPED ? "ignored" : "applied");
        } catch (AppStoreUnavailableException e) {
            metrics.notified("unavailable");
            throw e;
        }
    }

    /**
     * 구독 한 줄(originalTransactionId)의 지금 상태를 Apple 에 다시 물어 아는 행에 반영한다. 서버 알림과 대사 배치(#185)가 같이 쓴다.
     *
     * @return CHANGED 권한 기준(status·만료·갱신 여부)이 바뀜 · UNCHANGED 같음 · SKIPPED 아직 계정에 안 묶였거나 Apple 이 모르는 구독
     * @throws AppStoreUnavailableException Apple 에 물을 수 없을 때
     */
    public SubscriptionRefresh refresh(String originalTransactionId) {
        // 1. 아는 구독인지 먼저 본다(단건 조회) — 모르는 구독이면 Apple 에 묻지 않는다
        if (subscriptionRepository.findByPurchaseToken(originalTransactionId).isEmpty()) {
            log.info("App Store subscription not linked to any account yet; waiting for the app");
            return SubscriptionRefresh.SKIPPED;
        }
        // 2. 트랜잭션 없이 Apple 에 묻는다
        Optional<AppStoreSubscription> fetched = askApple(originalTransactionId);
        if (fetched.isEmpty()) {
            log.info("App Store does not know the transaction; ignored");
            return SubscriptionRefresh.SKIPPED;
        }
        // 3. 짧은 쓰기 트랜잭션: 행을 다시 읽어 반영한다
        return writeRetryingOnConflict(() -> subscriptionRepository.findByPurchaseToken(originalTransactionId)
                .map(row -> {
                    String before = SubscriptionRefresh.snapshot(row);
                    apply(row, fetched.get());
                    subscriptionRepository.save(row);
                    subscriptionRepository.flush();
                    return before.equals(SubscriptionRefresh.snapshot(row))
                            ? SubscriptionRefresh.UNCHANGED : SubscriptionRefresh.CHANGED;
                })
                .orElse(SubscriptionRefresh.SKIPPED));
    }

    /** 인앱 결제 키가 있어 Apple 에 물을 수 있는가. 대사 배치가 묻기 전에 본다. */
    public boolean configured() {
        return appStore.configured();
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

    /** 확인 요청용: 키가 없거나 Apple 에 물을 수 없으면 503. */
    private Optional<AppStoreSubscription> fetch(String transactionId) {
        if (!appStore.configured()) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_VERIFICATION_UNAVAILABLE);
        }
        try {
            return askApple(transactionId);
        } catch (AppStoreUnavailableException e) {
            log.warn("App Store verification unavailable: {}", e.getMessage());
            throw new CustomException(ErrorCode.SUBSCRIPTION_VERIFICATION_UNAVAILABLE);
        }
    }

    /** Apple 조회를 서킷브레이커 뒤에서. 서킷이 열려 있으면 기다리지 않고 "지금은 못 묻는다"로 끝낸다. 재시도는 없다(사용자가 기다린다). */
    private Optional<AppStoreSubscription> askApple(String transactionId) {
        try {
            return guard.noRetry(CIRCUIT, () -> appStore.getSubscription(transactionId));
        } catch (CallNotPermittedException e) {
            throw new AppStoreUnavailableException("circuit open: " + e.getMessage(), e);
        }
    }

    /** 쓰기 트랜잭션. 같은 구독의 행을 다른 요청이 먼저 넣어 유니크에 걸리면, 새 트랜잭션에서 한 번 다시 읽어 그 행에 반영한다. */
    private <T> T writeRetryingOnConflict(Supplier<T> work) {
        try {
            return write.execute(status -> work.get());
        } catch (DataIntegrityViolationException e) {
            log.debug("Subscription row conflict; retrying once: {}", e.getMessage());
            return write.execute(status -> work.get());
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
