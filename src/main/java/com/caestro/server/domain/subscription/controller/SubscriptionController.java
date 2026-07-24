package com.caestro.server.domain.subscription.controller;

import com.caestro.server.domain.subscription.controller.api.SubscriptionApi;
import com.caestro.server.domain.subscription.dto.request.CreateSubscriptionRequest;
import com.caestro.server.domain.subscription.dto.response.SubscriptionResponse;
import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.service.SubscriptionService;
import com.caestro.server.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @PostMapping
    @Override
    public ResponseEntity<SubscriptionResponse> createSubscription(
            @Valid @RequestBody CreateSubscriptionRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Subscription subscription = subscriptionService.createSubscription(userDetails.getUserId(), request);
        // 방금 생성된 구독은 활성 상태
        return ResponseEntity.status(HttpStatus.CREATED).body(SubscriptionResponse.from(subscription, true));
    }

    @GetMapping("/me")
    @Override
    public ResponseEntity<SubscriptionResponse> getMySubscription(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(subscriptionService.getMySubscription(userDetails.getUserId()));
    }
}
