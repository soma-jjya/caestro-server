package com.caestro.server.domain.b2b.service;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 세션 삭제 시 B2bFill은 FK CASCADE로 제거된다. 남은 미참조 프레임 사본은 별도로 정리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class B2bCleanupScheduler {
    private final B2bImageService images;
    private final Clock clock;

    @Scheduled(cron = "${cleanup.b2b.cron:0 0 5 * * *}")
    @SchedulerLock(name = "b2bImageCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        int purged = images.purgeUnreferenced(LocalDateTime.now(clock).minusDays(1));
        log.info("B2B image cleanup batch end (purged={})", purged);
    }
}
