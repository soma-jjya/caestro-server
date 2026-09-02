package com.caestro.server.domain.auth.controller.api;

import com.caestro.server.domain.auth.dto.request.GuestLoginRequest;
import com.caestro.server.domain.auth.dto.request.RefreshRequest;
import com.caestro.server.domain.auth.dto.request.SocialTokenLoginRequest;
import com.caestro.server.domain.auth.dto.response.TokenResponse;
import com.caestro.server.domain.user.entity.User;
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
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;

@Tag(name = "Auth", description = "인증 API")
public interface AuthApi {

    @Operation(
            summary = "소셜 로그인 콜백",
            description = "provider(kakao 등)에서 받은 인가코드로 JWT 토큰을 발급합니다."
    )
    @Parameter(name = "provider", description = "소셜 로그인 provider (예: kakao)", in = ParameterIn.PATH, required = true)
    @Parameter(name = "code", description = "인가코드", in = ParameterIn.QUERY, required = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "토큰 발급 성공",
                    content = @Content(schema = @Schema(implementation = TokenResponse.class))),
            @ApiResponse(responseCode = "400", description = "인가코드가 없음"),
            @ApiResponse(responseCode = "502", description = "외부 인증 서버 오류")
    })
    ResponseEntity<TokenResponse> socialCallback(String provider, String code,
            @Parameter(hidden = true) CustomUserDetails userDetails);

    @Operation(
            summary = "게스트(익명) 로그인",
            description = "디바이스 ID로 익명 유저를 생성/조회해 JWT를 발급합니다. 로그인 없이 세션 생성·TURN·촬영 등 "
                    + "대부분의 기능을 사용할 수 있으며, 이후 소셜 로그인 시 이 게스트 계정이 정식 계정으로 연동됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "토큰 발급 성공",
                    content = @Content(schema = @Schema(implementation = TokenResponse.class))),
            @ApiResponse(responseCode = "400", description = "디바이스 ID가 없음")
    })
    ResponseEntity<TokenResponse> guestLogin(@Valid @RequestBody GuestLoginRequest request);

    @Operation(
            summary = "소셜 로그인 (모바일 SDK 토큰)",
            description = "모바일 네이티브 SDK가 발급받은 토큰으로 JWT를 발급합니다. "
                    + "(카카오=access token, 구글=ID token, 애플=identity token — 모두 accessToken 필드로 전달) "
                    + "authorization code 교환 단계가 없어 redirect_uri에 의존하지 않으며 Android·iOS 공통으로 사용됩니다. "
                    + "애플은 authorizationCode를 함께 보내면 탈퇴 시 애플 연결 해제(revoke)용 토큰을 확보합니다."
    )
    @Parameter(name = "provider", description = "소셜 로그인 provider (예: kakao, google, apple)", in = ParameterIn.PATH, required = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "토큰 발급 성공",
                    content = @Content(schema = @Schema(implementation = TokenResponse.class))),
            @ApiResponse(responseCode = "400", description = "액세스토큰이 없음"),
            @ApiResponse(responseCode = "502", description = "외부 인증 서버 오류")
    })
    ResponseEntity<TokenResponse> socialLoginByToken(String provider, @Valid @RequestBody SocialTokenLoginRequest request,
            @Parameter(hidden = true) CustomUserDetails userDetails);

    @Operation(
            summary = "액세스토큰 재발급",
            description = "리프레시토큰으로 만료된 액세스토큰을 재발급합니다. (토큰 rotation)"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재발급 성공",
                    content = @Content(schema = @Schema(implementation = TokenResponse.class))),
            @ApiResponse(responseCode = "400", description = "리프레시토큰이 없음"),
            @ApiResponse(responseCode = "401", description = "유효하지 않은 토큰")
    })
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request);

    @Operation(
            summary = "로그아웃",
            description = "Redis에서 리프레시토큰을 삭제합니다. 이후 재발급이 불가능합니다."
    )
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그아웃 완료"),
            @ApiResponse(responseCode = "401", description = "토큰이 없음")
    })
    ResponseEntity<?> logout(CustomUserDetails userDetails);

    @Operation(
            summary = "내 정보 조회",
            description = "액세스토큰으로 로그인한 유저 정보를 조회합니다."
    )
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = User.class))),
            @ApiResponse(responseCode = "401", description = "토큰이 없음"),
            @ApiResponse(responseCode = "404", description = "유저를 찾을 수 없음")
    })
    ResponseEntity<User> getMe(CustomUserDetails userDetails);
}
