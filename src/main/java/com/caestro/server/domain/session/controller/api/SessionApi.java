package com.caestro.server.domain.session.controller.api;

import com.caestro.server.domain.session.dto.response.SessionResponse;
import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Session", description = "촬영 세션 API")
public interface SessionApi {

    @Operation(
            summary = "세션 단건 조회",
            description = "세션 ID로 촬영 세션의 상세 정보를 조회합니다. "
                    + "세션 참여자(디렉터/촬영자) 또는 관리자만 조회할 수 있습니다."
    )
    @SecurityRequirement(name = "BearerAuth")
    @Parameter(name = "sessionId", description = "조회할 세션 ID", in = ParameterIn.PATH, required = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = SessionResponse.class))),
            @ApiResponse(responseCode = "401", description = "토큰이 없음"),
            @ApiResponse(responseCode = "403", description = "세션에 접근할 권한이 없음"),
            @ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음")
    })
    ResponseEntity<SessionResponse> getSession(Long sessionId, CustomUserDetails userDetails);
}
