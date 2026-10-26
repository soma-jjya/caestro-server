package com.caestro.server.domain.subscription.service;

import com.caestro.server.global.exception.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 스토어 결제 확인·알림 처리 결과 집계 (#184). Grafana 에서 "지금 Apple/Google 확인이 깨지고 있는가"를 보기 위한 최소 지표.
 * <ul>
 *   <li>{@code subscription.verify{store,result}} — result: ok · invalid(400) · rejected(403) · conflict(409) · unavailable(503) · error</li>
 *   <li>{@code subscription.notification{store,result}} — result: applied · ignored · unavailable</li>
 * </ul>
 */
final class SubscriptionMetrics {

    static final String VERIFY = "subscription.verify";
    static final String NOTIFICATION = "subscription.notification";

    private final MeterRegistry registry;
    private final String store;

    SubscriptionMetrics(MeterRegistry registry, String store) {
        this.registry = registry;
        this.store = store;
    }

    void verified(String result) {
        registry.counter(VERIFY, "store", store, "result", result).increment();
    }

    void notified(String result) {
        registry.counter(NOTIFICATION, "store", store, "result", result).increment();
    }

    /** 확인 실패의 ErrorCode 를 지표 태그로. HTTP 상태와 같은 묶음이라 대시보드에서 읽기 쉽다. */
    static String resultOf(ErrorCode code) {
        return switch (code) {
            case SUBSCRIPTION_PURCHASE_INVALID -> "invalid";
            case GUEST_ACCOUNT_NOT_ALLOWED, USER_NOT_FOUND -> "rejected";
            case SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER -> "conflict";
            case SUBSCRIPTION_VERIFICATION_UNAVAILABLE -> "unavailable";
            default -> "error";
        };
    }
}
