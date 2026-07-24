package com.caestro.server.domain.turn.controller.api;

import com.caestro.server.domain.turn.dto.response.TurnCredentialResponse;
import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@Tag(name = "TURN", description = "TURN 임시 자격증명 API")
public interface TurnApi {

    @Operation(summary = "TURN 임시 자격증명 발급",
            description = "WebRTC 연결에 사용할 STUN/TURN iceServers를 반환한다. "
                    + "TURN 항목은 use-auth-secret 방식의 시간제한 임시 자격증명(username/credential)을 포함하며, "
                    + "클라이언트는 ttl 만료 전 재요청한다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponse(responseCode = "200", description = "iceServers 발급 성공")
    @ApiResponse(responseCode = "401", description = "인증 필요")
    ResponseEntity<TurnCredentialResponse> getTurnCredentials(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails);
}
