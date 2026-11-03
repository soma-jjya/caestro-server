package com.caestro.server.domain.ops.dto.response;

import java.time.LocalDateTime;

/**
 * 사용자 목록 한 행: 가입 경로(kakao·google·apple·guest·deleted), 마지막 로그인이 온 앱(ANDROID·IOS·WEB, 기록 전이면 null),
 * 온 폰(기기 키 해시 앞 16자, 기록 전이면 null — 같은 값이면 같은 폰), 가입·마지막 로그인, 지금의 Pro.
 */
public record OpsUserResponse(
        Long id,
        String provider,
        String platform,
        String deviceKey,
        String nickname,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt,
        OpsProResponse pro
) {
}
