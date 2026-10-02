package com.caestro.server.domain.fill.service;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 함께 채우기 세션 정리. 사진이 DB에 있으므로 만료 뒤 남겨 둘 이유가 없다 — 세션·칸·이미지를 함께 지운다.
 * 세션 정리 배치와 같은 방식으로 ShedLock이 인스턴스 중 하나만 실행하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FillCleanupScheduler {

    private final FillService fillService;
    private final Clock clock;

    @Scheduled(cron = "${cleanup.fill.cron:0 30 4 * * *}")
    @SchedulerLock(name = "fillCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        LocalDateTime now = LocalDateTime.now(clock);
        int purged = fillService.purgeExpired(now);
        log.info("Fill cleanup batch end (purgedSessions={})", purged);
    }
}
