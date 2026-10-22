package com.caestro.server.domain.dailylimit.service;

import com.caestro.server.domain.dailylimit.dto.response.DailyUsageResponse;
import com.caestro.server.domain.dailylimit.entity.DailyUsage;
import com.caestro.server.domain.dailylimit.enums.DailyAction;
import com.caestro.server.domain.dailylimit.repository.DailyUsageRepository;
import com.caestro.server.domain.subscription.service.SubscriptionService;
import com.caestro.server.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 무료 사용자의 하루 한도 (contracts/fourcut-daily-limit.md). 지금은 네컷 저장 하루 3회.
 *
 * <p>프리미엄 구독 중이면 한도도 기록도 없다. 날은 limits.zone(기본 Asia/Seoul) 자정에 바뀐다.
 * 사용 기록은 한 번에 한 행이고, 유니크 키 둘(키·순번)이 재시도 중복과 동시 저장 초과를 잠금 없이 막는다.
 * 유니크 충돌이 나면 새 트랜잭션에서 다시 본다.
 */
@Slf4j
@Service
public class DailyUsageService {

    private static final int MAX_ATTEMPTS = 3;

    private final DailyUsageRepository repository;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ZoneId zone;
    private final int fourcutSaveLimit;

    public DailyUsageService(DailyUsageRepository repository,
                             SubscriptionService subscriptionService,
                             UserRepository userRepository,
                             PlatformTransactionManager transactionManager,
                             Clock clock,
                             @Value("${limits.zone:Asia/Seoul}") String zone,
                             @Value("${limits.fourcut-save.daily-free:3}") int fourcutSaveLimit) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        this.fourcutSaveLimit = fourcutSaveLimit;
    }

    /** 오늘 얼마나 썼고 얼마나 남았는가. */
    public DailyUsageResponse status(Long userId, DailyAction action) {
        LocalDate today = today();
        if (subscriptionService.isActivePremium(userId)) {
            return DailyUsageResponse.unlimited(today, resetsAt(today));
        }
        int limit = limitOf(action);
        int used = used(userId, today, action);
        return DailyUsageResponse.counted(used < limit, limit, used, today, resetsAt(today));
    }

    /**
     * 하루 한도 하나를 쓴다. 같은 키는 그날 한 번만 센다(이미 센 키면 다시 허용).
     * 한도가 찼으면 기록하지 않고 allowed=false.
     */
    public DailyUsageResponse consume(Long userId, DailyAction action, String key) {
        LocalDate today = today();
        if (subscriptionService.isActivePremium(userId)) {
            return DailyUsageResponse.unlimited(today, resetsAt(today));
        }
        int limit = limitOf(action);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Boolean allowed = transaction.execute(status -> {
                    if (repository.existsByUserIdAndUsageDateAndActionTypeAndUsageKey(userId, today, action, key)) {
                        return true;
                    }
                    int used = used(userId, today, action);
                    if (used >= limit) {
                        return false;
                    }
                    repository.saveAndFlush(DailyUsage.builder()
                            .user(userRepository.getReferenceById(userId))
                            .usageDate(today)
                            .actionType(action)
                            .slot(used + 1)
                            .usageKey(key)
                            .build());
                    return true;
                });
                int used = used(userId, today, action);
                return DailyUsageResponse.counted(Boolean.TRUE.equals(allowed), limit, used, today, resetsAt(today));
            } catch (DataIntegrityViolationException e) {
                // 같은 순번이나 같은 키를 다른 요청이 먼저 썼다: 새로 읽어 다시 판단한다
                log.debug("Daily usage conflict (attempt {}): user={} action={}", attempt, userId, action);
            }
        }
        boolean counted = repository.existsByUserIdAndUsageDateAndActionTypeAndUsageKey(userId, today, action, key);
        return DailyUsageResponse.counted(counted, limit, used(userId, today, action), today, resetsAt(today));
    }

    /** 정리 배치: retentionDays 보다 오래된 기록을 지운다. */
    public int purgeOlderThan(int retentionDays) {
        Integer deleted = transaction.execute(status -> repository.deleteOlderThan(today().minusDays(retentionDays)));
        return deleted == null ? 0 : deleted;
    }

    private int used(Long userId, LocalDate today, DailyAction action) {
        return (int) repository.countByUserIdAndUsageDateAndActionType(userId, today, action);
    }

    private int limitOf(DailyAction action) {
        return switch (action) {
            case FOURCUT_SAVE -> fourcutSaveLimit;
        };
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    private OffsetDateTime resetsAt(LocalDate day) {
        return day.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    }
}
