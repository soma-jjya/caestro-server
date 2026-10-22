package com.caestro.server.domain.subscription.enums;

/**
 * 구독이 시작된 곳.
 * MANUAL 은 결제 없이 POST /subscriptions 로 만든 행(개발·운영자 지급용, 기본 비활성).
 */
public enum SubscriptionStore {

    GOOGLE_PLAY, APP_STORE, MANUAL
}
