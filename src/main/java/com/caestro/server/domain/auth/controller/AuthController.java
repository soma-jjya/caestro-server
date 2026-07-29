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
import com.caestro.server.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/guest")
    @Override
    public ResponseEntity<TokenResponse> guestLogin(@Valid @RequestBody GuestLoginRequest request) {
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
        return ResponseEntity.ok(authService.socialLoginByToken(provider, request.accessToken(), guestUserId));
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
}
