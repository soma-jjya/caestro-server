package com.caestro.server.domain.subscription.service;

import com.caestro.server.domain.subscription.dto.request.CreateSubscriptionRequest;
import com.caestro.server.domain.subscription.dto.response.SubscriptionResponse;
import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionPlan;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.repository.SubscriptionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final int DEFAULT_DURATION_DAYS = 30;

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    /**
     * 구독을 생성한다.
     * 실제 PG 결제 연동은 이번 스코프 밖이며, 결제 성공을 가정하고 구독 상태만 기록한다.
     * (TODO: 추후 결제 콜백 성공 시점에 이 로직이 호출되도록 연결될 자리)
     * 이미 활성 구독이 있는 사용자의 재요청은 보수적으로 거부한다(중복 결제/상태 꼬임 방지, 실서비스 정책 확정 필요).
     *
     * @param userId  인증된 사용자 ID (요청 바디의 user_id는 신뢰하지 않음)
     * @param request plan(optional, 기본 PREMIUM), duration(optional, 기본 30일)
     * @return 저장된 Subscription 엔티티
     * @throws CustomException INVALID_SUBSCRIPTION_PLAN / SUBSCRIPTION_ALREADY_ACTIVE / USER_NOT_FOUND
     */
    @Transactional
    public Subscription createSubscription(Long userId, CreateSubscriptionRequest request) {
        // 1. 플랜 결정 (없으면 기본 PREMIUM, 잘못된 값이면 400)
        SubscriptionPlan plan = request.plan() == null
                ? SubscriptionPlan.PREMIUM
                : SubscriptionPlan.from(request.plan())
                        .orElseThrow(() -> new CustomException(ErrorCode.INVALID_SUBSCRIPTION_PLAN));

        // 2. 이미 활성 구독이 있으면 거부 (보수적 정책)
        if (isActivePremium(userId)) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_ALREADY_ACTIVE);
        }

        // 3. 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 게스트(익명) 계정은 결제/구독 불가 — 소셜 로그인(계정 연동)이 선행되어야 한다
        if (user.isGuest()) {
            throw new CustomException(ErrorCode.GUEST_ACCOUNT_NOT_ALLOWED);
        }

        // 4. 기간 계산 (요청 없으면 기본 30일)
        int durationDays = request.duration() != null ? request.duration() : DEFAULT_DURATION_DAYS;
        LocalDateTime startedAt = LocalDateTime.now();
        LocalDateTime expiresAt = startedAt.plusDays(durationDays);

        // 5. 저장 (결제 성공 가정, status=ACTIVE)
        Subscription subscription = Subscription.builder()
                .user(user)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(startedAt)
                .expiresAt(expiresAt)
                .build();

        return subscriptionRepository.save(subscription);
    }

    /**
     * 인증된 사용자의 구독 상태를 조회한다.
     * DB의 status 값을 그대로 믿지 않고, 만료 시각을 응답 시점에 재계산해 active를 결정한다.
     *
     * @param userId 인증된 사용자 ID
     * @return 구독 응답 (이력이 없으면 비활성 응답)
     */
    @Transactional(readOnly = true)
    public SubscriptionResponse getMySubscription(Long userId) {
        return subscriptionRepository.findFirstByUserIdOrderByExpiresAtDesc(userId)
                .map(subscription -> SubscriptionResponse.from(subscription, isActive(subscription)))
                .orElseGet(SubscriptionResponse::none);
    }

    /**
     * 사용자가 현재 활성 프리미엄 구독자인지 판별한다. (구독 상태 가드/일일 한도 우회 등에서 재사용)
     *
     * @param userId 확인할 사용자 ID
     * @return status=ACTIVE 이고 만료 시각이 현재 이후이면 true
     */
    @Transactional(readOnly = true)
    public boolean isActivePremium(Long userId) {
        return subscriptionRepository.existsByUserIdAndStatusAndExpiresAtAfter(
                userId, SubscriptionStatus.ACTIVE, LocalDateTime.now());
    }

    /**
     * 활성 프리미엄 구독자가 아니면 접근을 차단하는 가드.
     * 프리미엄 전용 리소스의 컨트롤러/서비스에서 호출해 재사용한다.
     *
     * @param userId 확인할 사용자 ID
     * @throws CustomException SUBSCRIPTION_REQUIRED - 활성 구독자가 아님
     */
    public void requireActivePremium(Long userId) {
        if (!isActivePremium(userId)) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_REQUIRED);
        }
    }

    /**
     * 구독 엔티티의 활성 여부를 만료 시각 기준으로 재계산한다.
     */
    private boolean isActive(Subscription subscription) {
        return subscription.getStatus() == SubscriptionStatus.ACTIVE
                && subscription.getExpiresAt().isAfter(LocalDateTime.now());
    }
}
