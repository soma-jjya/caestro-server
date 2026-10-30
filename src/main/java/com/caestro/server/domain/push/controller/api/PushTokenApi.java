package com.caestro.server.domain.push.controller.api;

import com.caestro.server.domain.push.dto.request.RegisterPushTokenRequest;
import com.caestro.server.domain.push.dto.response.PushTestResponse;
import com.caestro.server.domain.push.dto.response.PushTokenResponse;
import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Push", description = "푸시 알림 기기 토큰 (contracts/push.md)")
public interface PushTokenApi {

    @Operation(summary = "기기 토큰 등록",
            description = "앱이 FCM 에서 받은 토큰을 내 계정에 묶습니다. 앱 시작·로그인·토큰 갱신 때마다 보내세요(멱등). "
                    + "같은 토큰이 다른 계정에 묶여 있었으면 이 계정으로 옮겨 옵니다. 게스트 계정도 됩니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "등록됨"),
            @ApiResponse(responseCode = "400", description = "platform·token 누락"),
            @ApiResponse(responseCode = "401", description = "토큰이 없음")
    })
    ResponseEntity<PushTokenResponse> register(RegisterPushTokenRequest request, CustomUserDetails userDetails);

    @Operation(summary = "기기 토큰 해제",
            description = "로그아웃하거나 알림을 끌 때. 내 토큰이 아니면 404.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "해제됨"),
            @ApiResponse(responseCode = "404", description = "등록된 토큰이 없음")
    })
    ResponseEntity<Void> unregister(@Parameter(description = "FCM 토큰", required = true) String token,
                                    CustomUserDetails userDetails);

    @Operation(summary = "테스트 알림",
            description = "내 모든 기기로 테스트 알림을 보냅니다. 앱 연동 확인용. tokens 와 sent 가 다르면 서버 로그에서 FCM 응답을 보세요.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "발송 결과")
    })
    ResponseEntity<PushTestResponse> test(CustomUserDetails userDetails);
}
