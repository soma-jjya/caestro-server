package com.caestro.server.domain.subscription.controller.api;

import com.caestro.server.domain.subscription.dto.request.CreateSubscriptionRequest;
import com.caestro.server.domain.subscription.dto.request.GooglePlayPurchaseRequest;
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
            summary = "구독 생성 (수동 부여)",
            description = "결제 없이 인증된 사용자의 구독을 생성합니다. subscription.manual-grant-enabled=true 일 때만 동작하며 "
                    + "기본(운영)은 403 입니다. 앱의 실제 결제는 POST /subscriptions/google-play 를 씁니다. "
                    + "이미 활성 구독이 있으면 400을 반환합니다."
    )
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "구독 생성 성공",
                    content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
            @ApiResponse(responseCode = "400", description = "이미 활성 구독 존재 / 유효하지 않은 plan·duration"),
            @ApiResponse(responseCode = "401", description = "토큰이 없음"),
            @ApiResponse(responseCode = "403", description = "수동 부여가 꺼져 있음 (운영 기본)")
    })
    ResponseEntity<SubscriptionResponse> createSubscription(CreateSubscriptionRequest request, CustomUserDetails userDetails);

    @Operation(
            summary = "Google Play 구독 확인",
            description = "앱이 Google Play 결제 직후(또는 구매 복원 때) 구매 토큰을 보냅니다. 서버가 Google Play Developer API 로 "
                    + "구독 상태를 확인해 이 계정의 구독으로 기록하고 현재 상태를 돌려줍니다. 같은 토큰을 다시 보내면 최신 상태로 고칩니다. "
                    + "결제 때 넣은 obfuscatedAccountId(사용자 id 의 SHA-256 hex)가 다른 계정이면 409 입니다."
    )
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "확인 완료 (active 로 이용 가능 여부. 결제 대기 중이면 active=false, storeState=SUBSCRIPTION_STATE_PENDING)",
                    content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Google 이 모르는 토큰 / 다른 상품"),
            @ApiResponse(responseCode = "401", description = "토큰이 없음"),
            @ApiResponse(responseCode = "403", description = "게스트 계정 (소셜 로그인 필요)"),
            @ApiResponse(responseCode = "409", description = "다른 계정에 연결된 구매"),
            @ApiResponse(responseCode = "503", description = "서비스 계정 키 미설정 또는 Google 일시 장애 (재시도)")
    })
    ResponseEntity<SubscriptionResponse> verifyGooglePlay(GooglePlayPurchaseRequest request, CustomUserDetails userDetails);

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
