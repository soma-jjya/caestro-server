package com.caestro.server.domain.ops.dto.response;

import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionPlan;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import java.time.LocalDateTime;

/**
 * 구독 한 행. 구매 토큰은 싣지 않는다. {@code active} 는 ACTIVE 이고 만료 전인지(갱신 알림 전 잠깐 어긋날 수 있다).
 * 사용자 칸(userId·nickname·provider)은 구독 목록에서만 채우고, 사용자 상세의 이력에서는 null 이다.
 */
public record OpsSubscriptionResponse(
        Long id,
        Long userId,
        String nickname,
        String provider,
        SubscriptionStore store,
        SubscriptionPlan plan,
        String productId,
        String basePlanId,
        SubscriptionStatus status,
        String storeState,
        boolean active,
        LocalDateTime startedAt,
        LocalDateTime expiresAt,
        Boolean autoRenewing,
        String latestOrderId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static OpsSubscriptionResponse of(Subscription s, LocalDateTime now, Long userId, String nickname, String provider) {
        boolean active = s.getStatus() == SubscriptionStatus.ACTIVE && s.getExpiresAt() != null && s.getExpiresAt().isAfter(now);
        return new OpsSubscriptionResponse(s.getId(), userId, nickname, provider, s.getStore(), s.getPlan(), s.getProductId(),
                s.getBasePlanId(), s.getStatus(), s.getStoreState(), active, s.getStartedAt(), s.getExpiresAt(),
                s.getAutoRenewing(), s.getLatestOrderId(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
