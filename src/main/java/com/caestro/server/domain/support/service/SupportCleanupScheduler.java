package com.caestro.server.domain.support.service;

import com.caestro.server.domain.support.entity.SupportTicket;
import com.caestro.server.domain.support.repository.SupportTicketRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 마지막 메시지 뒤 보관 기간(기본 1년)이 지난 문의를 사진까지 지운다 (#263). 알림함 정리와 같은 ShedLock 틀.
 * 한 번에 100건씩 id 순으로, 한 건이 실패해도 나머지는 지운다(실패한 행은 다음 날 다시 본다).
 */
@Slf4j
@Component
public class SupportCleanupScheduler {

    static final int BATCH = 100;

    private final SupportTicketRepository ticketRepository;
    private final SupportService supportService;
    private final Clock clock;
    private final int retentionDays;

    public SupportCleanupScheduler(SupportTicketRepository ticketRepository, SupportService supportService, Clock clock,
                                   @Value("${cleanup.support.retention-days:365}") int retentionDays) {
        this.ticketRepository = ticketRepository;
        this.supportService = supportService;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${cleanup.support.cron:0 40 5 * * *}")
    @SchedulerLock(name = "supportCleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    public void run() {
        runOnce(LocalDateTime.now(clock));
    }

    /** 테스트와 수동 실행이 ShedLock 없이 부를 수 있게 분리했다. */
    public int runOnce(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(retentionDays);
        int deleted = 0;
        int failed = 0;
        long afterId = 0;
        while (true) {
            List<SupportTicket> batch = ticketRepository.findByLastMessageAtBeforeAndIdGreaterThanOrderByIdAsc(cutoff, afterId, PageRequest.of(0, BATCH));
            if (batch.isEmpty()) {
                break;
            }
            for (SupportTicket ticket : batch) {
                afterId = ticket.getId();
                try {
                    supportService.purge(ticket);
                    deleted++;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Support cleanup failed (ticket={}, cause={}: {})", ticket.getId(), e.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
        log.info("Support cleanup batch end (deleted={}, failed={}, retentionDays={})", deleted, failed, retentionDays);
        return deleted;
    }
}
