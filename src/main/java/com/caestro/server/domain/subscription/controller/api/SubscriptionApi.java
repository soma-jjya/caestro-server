package com.caestro.server.domain.subscription.controller.api;

import com.caestro.server.domain.subscription.dto.request.CreateSubscriptionRequest;
import com.caestro.server.domain.subscription.dto.response.SubscriptionResponse;
import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Subscription", description = "구독 API")
public interface SubscriptionApi {

    @Operation(
            summary = "구독 생성",
            description = "인증된 사용자의 구독을 생성합니다. 실제 결제 연동은 포함되지 않으며 결제 성공을 가정합니다. "
                    + "이미 활성 구독이 있으면 400을 반환합니다."
    )
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "구독 생성 성공",
                    content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
            @ApiResponse(responseCode = "400", description = "이미 활성 구독 존재 / 유효하지 않은 plan·duration"),
            @ApiResponse(responseCode = "401", description = "토큰이 없음")
    })
    ResponseEntity<SubscriptionResponse> createSubscription(CreateSubscriptionRequest request, CustomUserDetails userDetails);

    @Operation(
            summary = "내 구독 상태 조회",
            description = "인증된 사용자의 구독 상태를 조회합니다. 만료 여부는 응답 시점에 재계산됩니다."
    )
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
            @ApiResponse(responseCode = "401", description = "토큰이 없음")
    })
    ResponseEntity<SubscriptionResponse> getMySubscription(CustomUserDetails userDetails);
}
