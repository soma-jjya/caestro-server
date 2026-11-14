package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.notification.entity.OutboxStatus;
import com.caestro.server.domain.notification.repository.NotificationOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** 끝난 아웃박스 행 정리 (#272). SENT·EXPIRED 는 7일, DEAD 는 사람이 볼 시간을 두고 30일 뒤. 알림함 정리와 같은 ShedLock 틀. */
@Slf4j
@Component
public class OutboxCleanupScheduler {

    private final NotificationOutboxRepository repository;
    private final Clock clock;
    private final int finishedDays;
    private final int deadDays;

    public OutboxCleanupScheduler(NotificationOutboxRepository repository, Clock clock,
                                  @Value("${cleanup.outbox.finished-days:7}") int finishedDays,
                                  @Value("${cleanup.outbox.dead-days:30}") int deadDays) {
        this.repository = repository;
        this.clock = clock;
        this.finishedDays = finishedDays;
        this.deadDays = deadDays;
    }

    @Scheduled(cron = "${cleanup.outbox.cron:0 50 5 * * *}")
    @SchedulerLock(name = "outboxCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        runOnce(LocalDateTime.now(clock));
    }

    @Transactional
    public int runOnce(LocalDateTime now) {
        int finished = repository.deleteFinishedBefore(List.of(OutboxStatus.SENT, OutboxStatus.EXPIRED), now.minusDays(finishedDays));
        int dead = repository.deleteFinishedBefore(List.of(OutboxStatus.DEAD), now.minusDays(deadDays));
        log.info("Outbox cleanup batch end (finished={}, dead={}, finishedDays={}, deadDays={})", finished, dead, finishedDays, deadDays);
        return finished + dead;
    }
}
