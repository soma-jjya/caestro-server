package com.caestro.server.domain.dailylimit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 지난 하루 사용 기록 정리. 한도는 오늘 것만 보므로 며칠 지난 기록은 필요 없다(문의 대응용으로 기본 30일 남긴다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyUsageCleanupScheduler {

    private final DailyUsageService dailyUsageService;

    @Value("${cleanup.daily-usage.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "${cleanup.daily-usage.cron:0 10 5 * * *}")
    @SchedulerLock(name = "dailyUsageCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        int deleted = dailyUsageService.purgeOlderThan(retentionDays);
        log.info("Daily usage cleanup batch end (deleted={})", deleted);
    }
}
