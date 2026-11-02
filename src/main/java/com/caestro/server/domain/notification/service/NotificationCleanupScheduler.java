package com.caestro.server.domain.notification.service;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관 기간(기본 90일)이 지난 알림을 지운다. 다른 정리 배치와 같이 ShedLock 으로 인스턴스 하나만 돈다 (#211).
 */
@Slf4j
@Component
public class NotificationCleanupScheduler {

    private final NotificationService notificationService;
    private final Clock clock;
    private final int retentionDays;

    public NotificationCleanupScheduler(NotificationService notificationService, Clock clock,
                                        @Value("${cleanup.notification.retention-days:90}") int retentionDays) {
        this.notificationService = notificationService;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${cleanup.notification.cron:0 20 5 * * *}")
    @SchedulerLock(name = "notificationCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        runOnce(LocalDateTime.now(clock));
    }

    /** 테스트와 수동 실행이 ShedLock 없이 부를 수 있게 분리했다. */
    public int runOnce(LocalDateTime now) {
        int deleted = notificationService.deleteCreatedBefore(now.minusDays(retentionDays));
        log.info("Notification cleanup batch end (deleted={}, retentionDays={})", deleted, retentionDays);
        return deleted;
    }
}
