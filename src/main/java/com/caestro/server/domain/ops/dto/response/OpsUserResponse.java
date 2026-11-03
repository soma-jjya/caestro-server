package com.caestro.server.domain.ops.dto.response;

import java.time.LocalDateTime;

/** 사용자 목록 한 행: 가입 경로(kakao·google·apple·guest·deleted), 가입·마지막 로그인, 지금의 Pro. */
public record OpsUserResponse(
        Long id,
        String provider,
        String nickname,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt,
        OpsProResponse pro
) {
}
