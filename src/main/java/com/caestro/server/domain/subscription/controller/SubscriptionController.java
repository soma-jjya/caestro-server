package com.caestro.server.domain.subscription.controller;

import com.caestro.server.domain.subscription.controller.api.SubscriptionApi;
import com.caestro.server.domain.subscription.dto.request.AppStorePurchaseRequest;
import com.caestro.server.domain.subscription.dto.request.CreateSubscriptionRequest;
import com.caestro.server.domain.subscription.dto.request.GooglePlayPurchaseRequest;
import com.caestro.server.domain.subscription.dto.response.SubscriptionResponse;
import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.service.AppStoreSubscriptionService;
import com.caestro.server.domain.subscription.service.GooglePlaySubscriptionService;
import com.caestro.server.domain.subscription.service.SubscriptionService;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController implements SubscriptionApi {

    private final SubscriptionService subscriptionService;
    private final GooglePlaySubscriptionService googlePlaySubscriptionService;
    private final AppStoreSubscriptionService appStoreSubscriptionService;

    // 결제 없이 구독을 만드는 수동 부여. 스토어 결제가 붙은 뒤로는 누구나 무료로 프리미엄이 되는 길이라 기본은 끈다(개발 환경에서만 켠다)
    @Value("${subscription.manual-grant-enabled:false}")
    private boolean manualGrantEnabled;

    @PostMapping
    @Override
    public ResponseEntity<SubscriptionResponse> createSubscription(
            @Valid @RequestBody CreateSubscriptionRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        if (!manualGrantEnabled) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_MANUAL_GRANT_DISABLED);
        }
        Subscription subscription = subscriptionService.createSubscription(userDetails.getUserId(), request);
        // 방금 생성된 구독은 활성 상태
        return ResponseEntity.status(HttpStatus.CREATED).body(SubscriptionResponse.from(subscription, true));
    }

    @PostMapping("/google-play")
    @Override
    public ResponseEntity<SubscriptionResponse> verifyGooglePlay(
            @Valid @RequestBody GooglePlayPurchaseRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(googlePlaySubscriptionService.verify(userDetails.getUserId(), request));
    }

    @PostMapping("/app-store")
    @Override
    public ResponseEntity<SubscriptionResponse> verifyAppStore(
            @Valid @RequestBody AppStorePurchaseRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(appStoreSubscriptionService.verify(userDetails.getUserId(), request));
    }

    @GetMapping("/me")
    @Override
    public ResponseEntity<SubscriptionResponse> getMySubscription(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(subscriptionService.getMySubscription(userDetails.getUserId()));
    }
}
