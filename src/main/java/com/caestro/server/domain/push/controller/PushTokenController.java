package com.caestro.server.domain.push.controller;

import com.caestro.server.domain.push.controller.api.PushTokenApi;
import com.caestro.server.domain.push.dto.request.RegisterPushTokenRequest;
import com.caestro.server.domain.push.dto.response.PushTestResponse;
import com.caestro.server.domain.push.dto.response.PushTokenResponse;
import com.caestro.server.domain.push.service.PushService;
import com.caestro.server.domain.push.service.PushTokenService;
import com.caestro.server.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me/push-token")
@RequiredArgsConstructor
public class PushTokenController implements PushTokenApi {

    private final PushTokenService pushTokenService;
    private final PushService pushService;

    @PutMapping
    @Override
    public ResponseEntity<PushTokenResponse> register(
            @Valid @RequestBody RegisterPushTokenRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(pushTokenService.register(userDetails.getUserId(), request));
    }

    // 토큰은 본문이 아니라 쿼리로 — DELETE 본문을 버리는 클라이언트·프록시가 있다
    @DeleteMapping
    @Override
    public ResponseEntity<Void> unregister(
            @RequestParam String token,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        pushTokenService.unregister(userDetails.getUserId(), token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    @Override
    public ResponseEntity<PushTestResponse> test(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(pushService.sendTest(userDetails.getUserId()));
    }
}
