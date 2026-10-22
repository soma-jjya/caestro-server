package com.caestro.server.domain.catalog.service;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 스튜디오 정리: 올리기만 하고 저장하지 않은 그림은 하루 지나면, 휴지통 항목은 기한(purgeAt)이 지나면 지운다
 * (포즈 덧씌우기는 S3 를 못 지우니 숨김으로 남긴다). 함께 채우기 정리와 같이 ShedLock 이 인스턴스 중 하나만 실행하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogCleanupScheduler {

    private final CatalogService catalogService;
    private final CatalogPoseService poseService;
    private final Clock clock;

    @Scheduled(cron = "${cleanup.catalog.cron:0 50 4 * * *}")
    @SchedulerLock(name = "catalogCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        runOnce(LocalDateTime.now(clock));
    }

    /** 테스트와 수동 실행이 ShedLock 없이 부를 수 있게 분리했다. */
    public int runOnce(LocalDateTime now) {
        // 휴지통을 먼저 비워야 그 그림이 고아로 잡혀 같은 배치에서 지워진다
        int trashed = catalogService.purgeExpiredTrash(now) + poseService.purgeExpiredTrash(now);
        int orphans = catalogService.purgeOrphanImages(now.minusDays(1));
        log.info("Catalog cleanup batch end (trashPurged={}, orphanImagesPurged={})", trashed, orphans);
        return trashed + orphans;
    }
}
