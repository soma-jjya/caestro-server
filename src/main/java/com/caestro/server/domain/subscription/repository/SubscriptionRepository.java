package com.caestro.server.domain.subscription.repository;

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
}
