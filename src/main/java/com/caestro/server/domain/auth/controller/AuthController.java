package com.caestro.server.domain.auth.controller;

import com.caestro.server.domain.auth.controller.api.AuthApi;
import com.caestro.server.domain.auth.dto.request.GuestLoginRequest;
import com.caestro.server.domain.auth.dto.request.RefreshRequest;
import com.caestro.server.domain.auth.dto.request.SocialTokenLoginRequest;
import com.caestro.server.domain.auth.dto.response.TokenResponse;
import com.caestro.server.domain.auth.service.AuthService;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import com.caestro.server.global.security.CustomUserDetails;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final RedisRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;

    // 기본 30/분 (#129): CGNAT 등 공유 IP의 정상 트래픽(분당 수~수십)은 통과시키고
    // 공격(실측 초당 수백 = 분당 수만)만 자르는 눈금 — 오탐 여부는 아래 429 지표로 관측해 조정한다
    @Value("${auth.ratelimit.guest-limit:30}")
    private int guestRateLimit;

    @Value("${auth.ratelimit.guest-window-seconds:60}")
    private int guestRateWindowSeconds;

    @PostMapping("/guest")
    @Override
    public ResponseEntity<TokenResponse> guestLogin(@Valid @RequestBody GuestLoginRequest request,
            HttpServletRequest httpRequest) {
        // 무인증 공개 + DB write 경로라 IP당 발급 속도를 제한한다 (#125, 스탬피드 실측 http p90 5.89s)
        if (!rateLimiter.tryAcquire("rl:guest:" + clientIp(httpRequest),
                guestRateLimit, Duration.ofSeconds(guestRateWindowSeconds))) {
            // 정상 시간대에 이 카운터가 찍히면 공유 IP 오탐 신호 → 한도(추정치 30)를 실측으로 교정 (#129)
            meterRegistry.counter("auth.guest.rate_limited").increment();
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return ResponseEntity.ok(authService.guestLogin(request.deviceId()));
    }

    @GetMapping("/{provider}/callback")
    @Override
    public ResponseEntity<TokenResponse> socialCallback(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        if (code == null || code.isBlank()) {
            throw new CustomException(ErrorCode.MISSING_AUTH_CODE);
        }
        // 게스트 JWT를 함께 보냈다면 그 userId로 계정 연동(업그레이드) 처리
        Long guestUserId = (userDetails != null) ? userDetails.getUserId() : null;
        return ResponseEntity.ok(authService.socialLogin(provider, code, guestUserId));
    }

    @PostMapping("/{provider}/token")
    @Override
    public ResponseEntity<TokenResponse> socialLoginByToken(
            @PathVariable String provider,
            @Valid @RequestBody SocialTokenLoginRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long guestUserId = (userDetails != null) ? userDetails.getUserId() : null;
        return ResponseEntity.ok(authService.socialLoginByToken(
                provider, request.accessToken(), guestUserId, request.authorizationCode(), request.nickname()));
    }

    @PostMapping("/refresh")
    @Override
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refreshAccessToken(request.refreshToken()));
    }

    @PostMapping("/logout")
    @Override
    public ResponseEntity<?> logout(@AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.logout(userDetails.getUserId());
        return ResponseEntity.ok(Map.of("message", "로그아웃 완료"));
    }

    @GetMapping("/me")
    @Override
    public ResponseEntity<User> getMe(@AuthenticationPrincipal CustomUserDetails userDetails) {
        User user = userRepository.findById(userDetails.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return ResponseEntity.ok(user);
    }

    /** ALB 뒤에서는 X-Forwarded-For의 첫 값이 실제 클라이언트 IP다 (직접 연결이면 remoteAddr). */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
    }
}
