package com.caestro.server.domain.subscription.enums;

import java.util.Optional;

public enum SubscriptionPlan {

    PREMIUM;

    /**
     * 외부에서 수신한 문자열을 SubscriptionPlan으로 변환한다.
     * 값이 null이거나 정의되지 않은 플랜이면 빈 Optional을 반환한다.
     *
     * @param value 변환할 플랜 문자열
     * @return 매칭되는 SubscriptionPlan (없으면 Optional.empty())
     */
    public static Optional<SubscriptionPlan> from(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(SubscriptionPlan.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
