package com.caestro.server.domain.subscription.dto.response;

import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionPlan;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import java.time.LocalDateTime;

public record SubscriptionResponse(
        boolean active,
        SubscriptionPlan plan,
        SubscriptionStatus status,
        LocalDateTime startedAt,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {

    /**
     * 구독 엔티티를 응답 DTO로 변환한다.
     * active는 DB 값을 그대로 믿지 않고 호출 시점에 재계산한 결과를 전달받아 채운다.
     *
     * @param subscription 구독 엔티티
     * @param active       응답 시점 재계산된 활성 여부
     */
    public static SubscriptionResponse from(Subscription subscription, boolean active) {
        return new SubscriptionResponse(
                active,
                subscription.getPlan(),
                subscription.getStatus(),
                subscription.getStartedAt(),
                subscription.getExpiresAt(),
                subscription.getCreatedAt()
        );
    }

    /**
     * 구독 이력이 전혀 없는 사용자를 위한 비활성 응답.
     */
    public static SubscriptionResponse none() {
        return new SubscriptionResponse(false, null, null, null, null, null);
    }
}
