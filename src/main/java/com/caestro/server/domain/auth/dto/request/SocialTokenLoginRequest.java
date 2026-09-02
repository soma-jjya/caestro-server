package com.caestro.server.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SocialTokenLoginRequest(

    @NotBlank(message = "액세스토큰은 필수입니다")
    String accessToken,

    // 애플 전용(선택): 탈퇴 시 애플 연결 해제(revoke)에 쓸 refresh token 교환용 1회성 코드
    String authorizationCode
) {
}
