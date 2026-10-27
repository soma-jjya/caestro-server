package com.caestro.server.domain.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.caestro.server.domain.subscription.appstore.AppStoreFixtures;
import com.caestro.server.domain.subscription.appstore.AppStoreServerApi;
import com.caestro.server.domain.subscription.appstore.AppStoreSubscription;
import com.caestro.server.domain.subscription.dto.request.AppStorePurchaseRequest;
import com.caestro.server.domain.subscription.dto.request.GooglePlayPurchaseRequest;
import com.caestro.server.domain.subscription.dto.response.SubscriptionResponse;
import com.caestro.server.domain.subscription.googleplay.GooglePlayPublisher;
import com.caestro.server.domain.subscription.googleplay.GooglePlaySubscription;
import com.caestro.server.domain.subscription.repository.SubscriptionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * #184 재현 — 스토어 결제 확인이 DB 트랜잭션 안에서 Google/Apple 을 기다린다.
 * <ul>
 *   <li>스토어 호출 시점에 트랜잭션이 살아 있으면 그 사이 DB 커넥션(Hikari 10개)이 묶인다. 스토어가 느린 날 결제와 무관한 API까지 번진다.</li>
 *   <li>같은 토큰으로 확인 요청 두 개가 동시에 오면 둘 다 INSERT 해 하나가 유니크(purchase_token)에 걸려 500 이 난다.</li>
 * </ul>
 * 실제 MySQL 필요 (docker-compose up -d / CI 서비스 컨테이너). 스토어는 목이다 — 목 안에서 "지금 트랜잭션이 있는가"를 기록한다.
 */
// RANDOM_PORT: 모의 서블릿 환경에는 WS ServerContainer가 없어 WS 설정 빈이 초기화되지 못한다 (contextLoads와 동일)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubscriptionVerifyTransactionTest {

    private static final String PRODUCT = "peakpic_pro";
    private static final String BUNDLE = "soma.project.caestro";

    @Autowired private GooglePlaySubscriptionService google;
    @Autowired private AppStoreSubscriptionService apple;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private UserRepository userRepository;

    @MockitoBean private GooglePlayPublisher publisher;
    @MockitoBean private AppStoreServerApi appStore;

    private User user;
    private String googleToken;
    private String appleOriginal;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // 게스트가 아닌 계정(소셜 연동 있음)만 결제를 확인할 수 있다
        user = userRepository.save(User.builder()
                .oauthProvider("test").oauthId("tx-" + suffix).nickname("결제").role(User.Role.USER).build());
        googleToken = "tx-google-" + suffix;
        appleOriginal = String.valueOf(2_000_000_000_000L + (System.nanoTime() % 1_000_000_000L));
        given(publisher.configured()).willReturn(true);
        given(appStore.configured()).willReturn(true);
    }

    @AfterEach
    void cleanup() {
        subscriptionRepository.findByPurchaseToken(googleToken).ifPresent(subscriptionRepository::delete);
        subscriptionRepository.findByPurchaseToken(appleOriginal).ifPresent(subscriptionRepository::delete);
        userRepository.delete(user);
    }

    @Test
    @DisplayName("[재현→방어] Google 에 묻는 동안 DB 트랜잭션이 열려 있지 않다")
    void googleCall_runsOutsideTransaction() {
        AtomicBoolean inTransaction = new AtomicBoolean(true);
        given(publisher.getSubscription(googleToken)).willAnswer(invocation -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(googlePurchase(GooglePlaySubscriptionService.accountHash(user.getId())));
        });

        SubscriptionResponse response = google.verify(user.getId(), new GooglePlayPurchaseRequest(googleToken, PRODUCT));

        assertThat(response.active()).isTrue();
        assertThat(inTransaction).as("Google 호출 중 트랜잭션이 살아 있으면 커넥션이 묶인다").isFalse();
        assertThat(subscriptionRepository.findByPurchaseToken(googleToken)).isPresent();
    }

    @Test
    @DisplayName("[재현→방어] Apple 에 묻는 동안 DB 트랜잭션이 열려 있지 않다")
    void appleCall_runsOutsideTransaction() {
        AtomicBoolean inTransaction = new AtomicBoolean(true);
        given(appStore.getSubscription(anyString())).willAnswer(invocation -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(applePurchase(AppStoreSubscriptionService.appAccountToken(user.getId())));
        });
        String signed = AppStoreFixtures.jws("{\"transactionId\":\"" + appleOriginal + "\",\"originalTransactionId\":\"" + appleOriginal + "\"}");

        SubscriptionResponse response = apple.verify(user.getId(), new AppStorePurchaseRequest(signed, "peakpic_pro_monthly"));

        assertThat(response.active()).isTrue();
        assertThat(inTransaction).as("Apple 호출 중 트랜잭션이 살아 있으면 커넥션이 묶인다").isFalse();
        assertThat(subscriptionRepository.findByPurchaseToken(appleOriginal)).isPresent();
    }

    @Test
    @DisplayName("[재현→방어] 같은 토큰의 확인 요청 두 개가 동시에 와도 둘 다 성공하고 행은 하나다")
    void concurrentVerify_sameToken_bothSucceed() throws Exception {
        given(publisher.getSubscription(googleToken)).willAnswer(invocation -> {
            Thread.sleep(300);   // 둘 다 "행 없음"을 지난 뒤에 INSERT 하도록 Google 응답을 늦춘다
            return Optional.of(googlePurchase(GooglePlaySubscriptionService.accountHash(user.getId())));
        });
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Throwable> attempt = () -> {
            barrier.await();
            try {
                google.verify(user.getId(), new GooglePlayPurchaseRequest(googleToken, PRODUCT));
                return null;
            } catch (Throwable t) {
                return t;
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = pool.submit(attempt);
            Future<Throwable> second = pool.submit(attempt);
            assertThat(first.get()).as("첫 요청").isNull();
            assertThat(second.get()).as("두 번째 요청(유니크 충돌이 500 으로 새면 안 된다)").isNull();
        } finally {
            pool.shutdownNow();
        }
        assertThat(subscriptionRepository.findByPurchaseToken(googleToken)).isPresent();
    }

    private static GooglePlaySubscription googlePurchase(String accountHash) {
        Instant now = Instant.now();
        return new GooglePlaySubscription(GooglePlaySubscription.STATE_ACTIVE, "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                "GPA.test", null, accountHash, now.minusSeconds(60), true,
                List.of(new GooglePlaySubscription.LineItem(PRODUCT, "monthly", now.plusSeconds(86_400 * 30), true, "GPA.test")));
    }

    private AppStoreSubscription applePurchase(String appAccountToken) {
        Instant now = Instant.now();
        return new AppStoreSubscription(AppStoreSubscription.STATUS_ACTIVE, appleOriginal, appleOriginal,
                "peakpic_pro_monthly", BUNDLE, now.minusSeconds(60), now.plusSeconds(86_400 * 30), null,
                appAccountToken, true, "Sandbox");
    }
}
