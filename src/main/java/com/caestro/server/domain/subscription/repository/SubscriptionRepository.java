package com.caestro.server.domain.subscription.repository;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;

import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /**
     * 사용자의 가장 최근(만료일 기준) 구독을 조회한다. (GET /subscriptions/me 용)
     */
    Optional<Subscription> findFirstByUserIdOrderByExpiresAtDesc(Long userId);

    /**
     * 사용자에게 특정 상태이면서 만료 시각이 기준 시각 이후인 구독이 존재하는지 확인한다. (활성 판별 용)
     */
    boolean existsByUserIdAndStatusAndExpiresAtAfter(Long userId, SubscriptionStatus status, LocalDateTime now);

    /**
     * 스토어 구매 토큰으로 구독을 찾는다. (Google Play 확인·실시간 알림 용)
     */
    Optional<Subscription> findByPurchaseToken(String purchaseToken);
    /**
     * 대사 배치(#185) 대상: 스토어 구독 중 알림을 놓쳤을 때 어긋날 수 있는 행.
     * (a) ACTIVE 이고 만료가 [now, until) 안 · (b) [since, now) 에 만료됐고 환불·대체가 아닌 행 · (c) Google 결제 대기(PENDING).
     * 만료 임박 순. 호출 한도 때문에 Pageable 로 1회 상한을 둔다.
     */
    @Query("""
            select s from Subscription s
            where s.store in :stores and s.purchaseToken is not null
              and ((s.status = :active and s.expiresAt >= :now and s.expiresAt < :until)
                or (s.expiresAt >= :since and s.expiresAt < :now
                    and (s.storeState is null or s.storeState not in ('REVOKED', 'REPLACED')))
                or s.storeState = 'SUBSCRIPTION_STATE_PENDING')
            order by s.expiresAt asc
            """)
    List<Subscription> findReconcileTargets(@Param("stores") List<SubscriptionStore> stores,
                                            @Param("active") SubscriptionStatus active,
                                            @Param("now") LocalDateTime now,
                                            @Param("until") LocalDateTime until,
                                            @Param("since") LocalDateTime since,
                                            Pageable page);
}
