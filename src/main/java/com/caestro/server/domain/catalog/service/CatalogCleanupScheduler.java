package com.caestro.server.domain.catalog.service;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 스튜디오에서 올리기만 하고 프레임·배너로 저장하지 않은 그림을 하루 지나면 지운다.
 * 함께 채우기 정리와 같이 ShedLock 이 인스턴스 중 하나만 실행하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogCleanupScheduler {

    private final CatalogService catalogService;
    private final Clock clock;

    @Scheduled(cron = "${cleanup.catalog.cron:0 50 4 * * *}")
    @SchedulerLock(name = "catalogCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        runOnce(LocalDateTime.now(clock));
    }

    /** 테스트와 수동 실행이 ShedLock 없이 부를 수 있게 분리했다. */
    public int runOnce(LocalDateTime now) {
        int purged = catalogService.purgeOrphanImages(now.minusDays(1));
        log.info("Catalog cleanup batch end (orphanImagesPurged={})", purged);
        return purged;
    }
}
