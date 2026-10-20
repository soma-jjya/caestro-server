package com.caestro.server.domain.fill.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 함께 채우기 세션 정리. 세션·칸·사진(저장소)을 함께 지운다.
 * 세션 정리 배치와 같은 방식으로 ShedLock이 인스턴스 중 하나만 실행하게 한다.
 * 세션마다 트랜잭션을 따로 걸어 하나가 실패해도 나머지는 지워지고, 실패는 로그·지표로 남긴다 (#162).
 */
@Slf4j
@Component
public class FillCleanupScheduler {

    /** 한 번 실행한 결과. purged + failed = 만료 세션 수. */
    public record Result(int purged, int failed) {
    }

    private final FillService fillService;
    private final Clock clock;
    // 0이어야 정상. 찍히면 어떤 세션이 왜 안 지워지는지 warn 로그를 본다
    private final Counter failed;

    public FillCleanupScheduler(FillService fillService, Clock clock, MeterRegistry registry) {
        this.fillService = fillService;
        this.clock = clock;
        this.failed = Counter.builder("fill.cleanup.failed")
                .description("만료 정리에서 삭제에 실패한 함께 채우기 세션 수")
                .register(registry);
    }

    @Scheduled(cron = "${cleanup.fill.cron:0 30 4 * * *}")
    @SchedulerLock(name = "fillCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        runOnce(LocalDateTime.now(clock));
    }

    /** 만료 세션을 하나씩 지운다. 테스트와 수동 실행이 ShedLock 없이 부를 수 있게 분리했다. */
    public Result runOnce(LocalDateTime now) {
        List<Long> expired = fillService.expiredSessionIds(now);
        int purged = 0;
        int failures = 0;
        for (Long sessionId : expired) {
            try {
                fillService.purgeSession(sessionId);
                purged++;
            } catch (RuntimeException e) {
                failures++;
                failed.increment();
                log.warn("Fill cleanup failed to purge session (sessionId={}, cause={}: {})",
                        sessionId, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        // 앞부분 "Fill cleanup batch end"는 Logs Insights 필터가 의존하므로 바꾸지 않는다
        log.info("Fill cleanup batch end (purged={}, failed={})", purged, failures);
        return new Result(purged, failures);
    }
}
