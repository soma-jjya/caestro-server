package com.caestro.server.domain.ops.repository;

/** 운영 요약의 구독 숫자 5개를 한 번에 센 결과 (#257). 모두 "지금 쓸 수 있는 구독"(ACTIVE, 만료 전) 안에서의 수. */
public record OpsSubscriptionCounts(long active, long googlePlay, long appStore, long manual, long autoRenewOff) {
}
