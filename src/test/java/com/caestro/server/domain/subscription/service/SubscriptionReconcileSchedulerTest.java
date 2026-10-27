package com.caestro.server.domain.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.subscription.appstore.AppStoreUnavailableException;
import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionPlan;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import com.caestro.server.domain.subscription.repository.SubscriptionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 대사 배치(#185)의 집계·격리 규칙. 스토어 재조회 자체는 서비스 테스트가 맡고, 여기서는 "행마다 따로, 실패는 세고, 키 없으면 건너뜀"만 본다.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionReconcileSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-26T04:40:00Z");

    @Mock private SubscriptionRepository repository;
    @Mock private GooglePlaySubscriptionService googlePlay;
    @Mock private AppStoreSubscriptionService appStore;

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private SubscriptionReconcileScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SubscriptionReconcileScheduler(repository, googlePlay, appStore,
                Clock.fixed(NOW, ZoneOffset.UTC), registry, 24, 3, 200);
    }

    private static Subscription row(SubscriptionStore store, String token) {
        return Subscription.builder()
                .plan(SubscriptionPlan.PREMIUM).status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.ofInstant(NOW.minusSeconds(86_400), ZoneOffset.UTC))
                .expiresAt(LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC))
                .store(store).purchaseToken(token)
                .build();
    }

    @Test
    @DisplayName("행마다 따로 맞춘다: 가운데 행이 실패해도 앞뒤 행은 처리되고 실패는 카운터로 남는다")
    void oneFailureDoesNotStopOthers() {
        given(repository.findReconcileTargets(any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(row(SubscriptionStore.GOOGLE_PLAY, "g1"),
                        row(SubscriptionStore.APP_STORE, "a1"),
                        row(SubscriptionStore.GOOGLE_PLAY, "g2")));
        given(googlePlay.configured()).willReturn(true);
        given(appStore.configured()).willReturn(true);
        given(googlePlay.refresh("g1")).willReturn(SubscriptionRefresh.CHANGED);
        given(appStore.refresh("a1")).willThrow(new AppStoreUnavailableException("down"));
        given(googlePlay.refresh("g2")).willReturn(SubscriptionRefresh.UNCHANGED);

        SubscriptionReconcileScheduler.Result result = scheduler.runOnce(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        assertThat(result).isEqualTo(new SubscriptionReconcileScheduler.Result(3, 1, 0, 1));
        assertThat(registry.counter("subscription.reconcile.failed").count()).isEqualTo(1);
        verify(googlePlay).refresh("g2");
    }

    @Test
    @DisplayName("키가 설정되지 않은 스토어의 행은 실패가 아니라 건너뜀으로 센다 (Google 연결 전 상태)")
    void unconfiguredStoreIsSkipped() {
        given(repository.findReconcileTargets(any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(row(SubscriptionStore.GOOGLE_PLAY, "g1"), row(SubscriptionStore.APP_STORE, "a1")));
        given(googlePlay.configured()).willReturn(false);
        given(appStore.configured()).willReturn(true);
        given(appStore.refresh("a1")).willReturn(SubscriptionRefresh.UNCHANGED);

        SubscriptionReconcileScheduler.Result result = scheduler.runOnce(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        assertThat(result).isEqualTo(new SubscriptionReconcileScheduler.Result(2, 0, 1, 0));
        verify(googlePlay, never()).refresh(anyString());
        assertThat(registry.counter("subscription.reconcile.failed").count()).isZero();
    }
}
