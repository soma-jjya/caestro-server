package com.caestro.server.domain.ops.dto.response;

import java.time.LocalDateTime;

/**
 * 운영 화면 맨 위 숫자. 사용자 수는 가입 경로별(탈퇴 제외)과 탈퇴, 오늘·7일 신규와 활성(마지막 로그인 기준).
 * 구독은 지금 활성인 행 수와 스토어별, 그중 자동 갱신을 끈 것(해지 예약).
 */
public record OpsStatsResponse(Users users, Subscriptions subscriptions, LocalDateTime generatedAt) {

    public record Users(long total, long kakao, long google, long apple, long guest, long deleted,
                       long newToday, long newLast7Days, long activeToday, long activeLast7Days) {
    }

    public record Subscriptions(long active, long googlePlay, long appStore, long manual, long autoRenewOff) {
    }
}
