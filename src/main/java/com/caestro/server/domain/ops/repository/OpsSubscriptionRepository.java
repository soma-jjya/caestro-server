package com.caestro.server.domain.ops.repository;

import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 운영 조회 전용 구독 집계. 앱 기능이 쓰는 SubscriptionRepository 와 떼어 둔다. */
public interface OpsSubscriptionRepository extends Repository<Subscription, Long> {

    /**
     * 지금 쓸 수 있는 구독(ACTIVE, 만료 전)을 한 번 훑어 전체·스토어별·해지 예약 수를 센다 (#257).
     * 전에는 count 5번이었고 인덱스가 없어 5번 다 풀스캔이었다. 인덱스 (status, expires_at, store, auto_renewing) 를 탄다.
     * sum 은 행이 없으면 null 이라 coalesce 로 0.
     */
    @Query("""
            select new com.caestro.server.domain.ops.repository.OpsSubscriptionCounts(
                count(s),
                coalesce(sum(case when s.store = :googlePlay then 1L else 0L end), 0L),
                coalesce(sum(case when s.store = :appStore then 1L else 0L end), 0L),
                coalesce(sum(case when s.store = :manual then 1L else 0L end), 0L),
                coalesce(sum(case when s.autoRenewing = false then 1L else 0L end), 0L))
            from Subscription s
            where s.status = :active and s.expiresAt > :now
            """)
    OpsSubscriptionCounts countActive(@Param("active") SubscriptionStatus active, @Param("now") LocalDateTime now,
                                      @Param("googlePlay") SubscriptionStore googlePlay, @Param("appStore") SubscriptionStore appStore,
                                      @Param("manual") SubscriptionStore manual);
}
