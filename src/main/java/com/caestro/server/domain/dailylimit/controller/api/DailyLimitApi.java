package com.caestro.server.domain.dailylimit.controller.api;

import com.caestro.server.domain.dailylimit.dto.request.DailyUsageConsumeRequest;
import com.caestro.server.domain.dailylimit.dto.response.DailyUsageResponse;
import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "DailyLimit", description = "무료 사용자 하루 한도 API")
public interface DailyLimitApi {

    @Operation(
            summary = "네컷 저장 한도 조회",
            description = "오늘(Asia/Seoul) 네컷을 몇 번 저장했고 몇 번 남았는지. 프리미엄 구독 중이면 unlimited=true."
    )
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = DailyUsageResponse.class))),
            @ApiResponse(responseCode = "401", description = "토큰이 없음")
    })
    ResponseEntity<DailyUsageResponse> fourcutStatus(CustomUserDetails userDetails);

    @Operation(
            summary = "네컷 저장 한도 사용",
            description = "완성한 네컷을 앨범에 처음 저장하기 전에 부릅니다. 같은 projectId 는 그날 한 번만 셉니다(재시도·재저장 안전). "
                    + "한도가 찼으면 기록하지 않고 allowed=false (HTTP 200). 프리미엄 구독 중이면 기록 없이 allowed=true, unlimited=true."
    )
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "allowed 로 허용 여부",
                    content = @Content(schema = @Schema(implementation = DailyUsageResponse.class))),
            @ApiResponse(responseCode = "400", description = "projectId 형식 오류"),
            @ApiResponse(responseCode = "401", description = "토큰이 없음")
    })
    ResponseEntity<DailyUsageResponse> consumeFourcut(DailyUsageConsumeRequest request, CustomUserDetails userDetails);
}
