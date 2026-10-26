package com.caestro.server.domain.subscription.service;

import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import com.caestro.server.domain.subscription.repository.SubscriptionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 구독 대사(reconciliation) 배치 (#185). 스토어 알림(RTDN · App Store Server Notifications)을 놓쳐도
 * 결제한 사용자가 Pro 를 잃지 않게, 어긋날 수 있는 행만 골라 스토어에 지금 상태를 다시 묻는다.
 *
 * <p>대상: (a) ACTIVE 인데 만료가 창(window-hours) 안 — 갱신 알림을 놓쳤다면 지금 안 늘리면 내일 풀린다.
 * (b) 최근(lookback-days) 만료됐고 환불·대체(REVOKED·REPLACED)가 아닌 행 — 이미 풀린 사용자의 갱신을 되살린다.
 * (c) Google 결제 대기(PENDING) — 느린 결제 수단의 완료는 알림 없이는 영원히 모른다.
 * 전체를 매일 재조회하지 않는 이유는 스토어 API 호출 한도다. 1회 상한(max-rows)을 둔다.
 *
 * <p>행마다 격리해 하나가 실패해도 나머지는 맞춘다(#162 와 같은 틀). 스토어 조회는 #184 의 경로(서킷, 재시도 없음)를 그대로 탄다.
 * 키가 설정되지 않은 스토어의 행은 실패가 아니라 건너뜀으로 센다.
 */
@Slf4j
@Component
public class SubscriptionReconcileScheduler {

    /** 한 번 실행한 결과. checked = changed + unchanged + skipped + failed. */
    public record Result(int checked, int changed, int skipped, int failed) {
    }

    private static final List<SubscriptionStore> STORES = List.of(SubscriptionStore.GOOGLE_PLAY, SubscriptionStore.APP_STORE);

    private final SubscriptionRepository subscriptionRepository;
    private final GooglePlaySubscriptionService googlePlay;
    private final AppStoreSubscriptionService appStore;
    private final Clock clock;
    // 0이어야 정상. 찍히면 어느 구독이 왜 안 맞춰졌는지 warn 로그를 본다
    private final Counter failed;
    private final int windowHours;
    private final int lookbackDays;
    private final int maxRows;

    public SubscriptionReconcileScheduler(SubscriptionRepository subscriptionRepository,
                                          GooglePlaySubscriptionService googlePlay,
                                          AppStoreSubscriptionService appStore,
                                          Clock clock,
                                          MeterRegistry registry,
                                          @Value("${subscription.reconcile.window-hours:24}") int windowHours,
                                          @Value("${subscription.reconcile.lookback-days:3}") int lookbackDays,
                                          @Value("${subscription.reconcile.max-rows:200}") int maxRows) {
        this.subscriptionRepository = subscriptionRepository;
        this.googlePlay = googlePlay;
        this.appStore = appStore;
        this.clock = clock;
        this.failed = Counter.builder("subscription.reconcile.failed")
                .description("대사 배치에서 스토어 재조회·반영에 실패한 구독 행 수")
                .register(registry);
        this.windowHours = windowHours;
        this.lookbackDays = lookbackDays;
        this.maxRows = maxRows;
    }

    @Scheduled(cron = "${subscription.reconcile.cron:0 40 4 * * *}")
    @SchedulerLock(name = "subscriptionReconcile", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        runOnce(LocalDateTime.now(clock));
    }

    /** 대상 행을 하나씩 스토어와 맞춘다. 테스트와 수동 실행이 ShedLock 없이 부를 수 있게 분리했다. */
    public Result runOnce(LocalDateTime now) {
        List<Subscription> targets = subscriptionRepository.findReconcileTargets(
                STORES, SubscriptionStatus.ACTIVE, now, now.plusHours(windowHours), now.minusDays(lookbackDays),
                PageRequest.of(0, maxRows));
        int changed = 0;
        int skipped = 0;
        int failures = 0;
        for (Subscription row : targets) {
            try {
                SubscriptionRefresh result = refresh(row);
                if (result == SubscriptionRefresh.CHANGED) {
                    changed++;
                } else if (result == SubscriptionRefresh.SKIPPED) {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failures++;
                failed.increment();
                log.warn("Subscription reconcile failed (subscriptionId={}, store={}, cause={}: {})",
                        row.getId(), row.getStore(), e.getClass().getSimpleName(), e.getMessage());
            }
        }
        // 앞부분 "Subscription reconcile end"는 Logs Insights 필터가 의존하므로 바꾸지 않는다
        log.info("Subscription reconcile end (checked={}, changed={}, skipped={}, failed={})",
                targets.size(), changed, skipped, failures);
        return new Result(targets.size(), changed, skipped, failures);
    }

    private SubscriptionRefresh refresh(Subscription row) {
        return switch (row.getStore()) {
            case GOOGLE_PLAY -> googlePlay.configured() ? googlePlay.refresh(row.getPurchaseToken()) : SubscriptionRefresh.SKIPPED;
            case APP_STORE -> appStore.configured() ? appStore.refresh(row.getPurchaseToken()) : SubscriptionRefresh.SKIPPED;
            case MANUAL -> SubscriptionRefresh.SKIPPED;
        };
    }
}
