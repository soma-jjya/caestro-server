package com.caestro.server.domain.ops.dto.response;

import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import java.time.LocalDateTime;

/** 지금 쓸 수 있는 구독 하나(만료가 가장 늦은 것). 목록 행과 상세의 Pro 칸. */
public record OpsProResponse(
        SubscriptionStore store,
        String basePlanId,
        SubscriptionStatus status,
        LocalDateTime expiresAt,
        Boolean autoRenewing
) {
    public static OpsProResponse from(Subscription subscription) {
        return new OpsProResponse(subscription.getStore(), subscription.getBasePlanId(), subscription.getStatus(),
                subscription.getExpiresAt(), subscription.getAutoRenewing());
    }
}
