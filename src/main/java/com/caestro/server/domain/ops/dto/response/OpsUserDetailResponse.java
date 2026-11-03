package com.caestro.server.domain.ops.dto.response;

import com.caestro.server.domain.user.entity.User;
import java.time.LocalDateTime;
import java.util.List;

/** 사용자 한 명: 목록 행에 더해 가린 소셜·기기 id, 알림 토큰 수, 구독 이력 전부(만료가 늦은 것부터). */
public record OpsUserDetailResponse(
        Long id,
        String provider,
        String platform,
        String nickname,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt,
        LocalDateTime deletedAt,
        String oauthIdMasked,
        String deviceIdMasked,
        User.Role role,
        long pushTokens,
        OpsProResponse pro,
        List<OpsSubscriptionResponse> subscriptions
) {
}
