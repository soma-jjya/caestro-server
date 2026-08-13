package com.caestro.server.domain.session.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료 세션 정리 배치의 스케줄 트리거.
 * 실제 정리 로직은 SessionCleanupService가 담당한다(트리거와 로직 분리 → 테스트·향후 이전 용이).
 *
 * 다중 인스턴스 환경에서 각 인스턴스가 @Scheduled를 독립 실행하면 배치가 중복 실행된다.
 * @SchedulerLock(ShedLock)으로 "여러 인스턴스 중 하나만" 실행되도록 보장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCleanupScheduler {

    private final SessionCleanupService sessionCleanupService;

    // 세션 보관 기간(일). 이보다 오래된(생성 기준) 세션을 정리한다.
    @Value("${cleanup.session.retention-days:30}")
    private long retentionDays;

    /**
     * 주기적으로 만료 세션을 정리한다. (기본: 매일 새벽 4시)
     * lockAtMostFor: 배치가 죽어도 이 시간 후 락 자동 해제(데드락 방지).
     * lockAtLeastFor: 최소 이 시간 락 유지 → 시계 오차로 인한 중복 실행 방지.
     */
    @Scheduled(cron = "${cleanup.session.cron:0 0 4 * * *}")
    @SchedulerLock(name = "sessionCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        log.info("Session cleanup batch start (retentionDays={}, cutoff={})", retentionDays, cutoff);
        int deleted = sessionCleanupService.cleanupSessionsCreatedBefore(cutoff);
        log.info("Session cleanup batch end (deletedSessions={})", deleted);
    }
}
