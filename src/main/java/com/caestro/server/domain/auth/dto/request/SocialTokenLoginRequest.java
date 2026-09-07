package com.caestro.server.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SocialTokenLoginRequest(

    @NotBlank(message = "액세스토큰은 필수입니다")
    String accessToken,

    // 애플 전용(선택): 탈퇴 시 애플 연결 해제(revoke)에 쓸 refresh token 교환용 1회성 코드
    String authorizationCode,

    // 애플 전용(선택, #134): 애플은 이름을 identity token이 아니라 앱에만 최초 1회 제공하므로
    // 클라이언트가 전달한다. 서버 검증 불가한 클라 주장 값 → 사용자 입력으로 취급해 길이를 제한한다.
    @Size(max = 50, message = "닉네임은 50자 이하여야 합니다")
    String nickname
) {
}
