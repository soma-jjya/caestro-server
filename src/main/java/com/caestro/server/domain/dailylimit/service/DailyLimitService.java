package com.caestro.server.domain.dailylimit.service;

import com.caestro.server.domain.dailylimit.entity.ShotDailyLimit;
import com.caestro.server.domain.dailylimit.repository.ShotDailyLimitRepository;
import com.caestro.server.domain.subscription.service.SubscriptionService;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DailyLimitService {

    private final ShotDailyLimitRepository shotDailyLimitRepository;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * 사용자의 오늘 일일 사용량을 확인하고, 한도 이내이면 1 증가시킨다.
     * 특정 기능에 종속되지 않는 범용 카운터로, actionType으로 어떤 액션을 카운트하는지 구분한다.
     * 활성 프리미엄 구독자는 한도 체크를 우회하며(카운트도 증가하지 않음), 날짜가 바뀌면 자동으로 새 카운트로 집계된다.
     *
     * <p>주의: 현재 shot_daily_limits 테이블에는 action_type 컬럼이 없어 actionType은 로직상 구분에 사용되지 않고
     * 단일 카운터로 동작한다. (여러 액션을 다른 한도로 구분하려면 스키마에 action_type 컬럼 추가 필요 — 후속 과제)
     *
     * @param userId     사용자 ID
     * @param actionType 카운트 대상 액션 타입 (현재는 미사용, 향후 확장용)
     * @param dailyLimit 하루 허용 횟수
     * @return 한도 이내여서 사용 가능하면 true, 한도 초과면 false
     */
    @Transactional
    public boolean checkAndIncrementDailyLimit(Long userId, String actionType, int dailyLimit) {
        // 1. 활성 프리미엄 구독자는 한도 없이 우회 (카운트 증가 없음)
        if (subscriptionService.isActivePremium(userId)) {
            return true;
        }

        // 2. 오늘자 카운터 조회 (없으면 count=0으로 신규 생성)
        LocalDate today = LocalDate.now(clock);
        ShotDailyLimit dailyLimit_ = shotDailyLimitRepository.findByUserIdAndDate(userId, today)
                .orElseGet(() -> ShotDailyLimit.builder()
                        .user(userReference(userId))
                        .date(today)
                        .count(0)
                        .build());

        // 3. 한도 초과면 증가 없이 차단
        if (dailyLimit_.getCount() >= dailyLimit) {
            return false;
        }

        // 4. 한도 이내면 1 증가 후 저장 (신규는 insert, 기존은 변경 감지/저장)
        dailyLimit_.increment();
        shotDailyLimitRepository.save(dailyLimit_);
        return true;
    }

    /**
     * FK 세팅용 User 프록시 참조 (불필요한 조회 없이 user_id만 세팅).
     */
    private User userReference(Long userId) {
        return userRepository.getReferenceById(userId);
    }
}
