package com.caestro.server.domain.user.controller.api;

import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@Tag(name = "User", description = "유저 API")
public interface UserApi {

    @Operation(summary = "회원 탈퇴",
            description = "본인 계정을 탈퇴한다. 개인정보를 즉시 익명화하고 촬영 결과물을 파기하며, "
                    + "토큰을 무효화한다. 완전 파기는 유예 후 배치가 수행한다.")
    @ApiResponse(responseCode = "204", description = "탈퇴 성공")
    @ApiResponse(responseCode = "401", description = "인증 실패")
    @ApiResponse(responseCode = "404", description = "존재하지 않거나 이미 탈퇴한 계정")
    ResponseEntity<Void> withdraw(@AuthenticationPrincipal CustomUserDetails userDetails);
}
