package com.caestro.server.domain.fill.service;

import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.notification.service.NotificationDispatcher;
import com.caestro.server.domain.push.sender.PushMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료 전날 안내 (#213): 앞으로 24시간 안에 만료하고 사진이 있는 세션의 주인에게 "내일 사라져요"를 세션당 한 번 보낸다.
 * 하루 한 번(기본 KST 09:00) 돌고 창이 주기와 같아 빠지지 않는다. 행마다 따로 처리해 하나가 실패해도 나머지는 간다(정리 배치와 같은 원칙).
 */
@Slf4j
@Component
public class FillExpiringNotifier {

    /** 한 번 실행한 결과. candidates = notified + skipped + failed. */
    public record Result(int candidates, int notified, int skipped, int failed) {
    }

    static final Duration WINDOW = Duration.ofHours(24);

    private final FillSessionRepository sessionRepository;
    private final NotificationDispatcher dispatcher;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Counter notified;
    // 0이어야 정상. 찍히면 어떤 세션이 왜 실패했는지 warn 로그를 본다
    private final Counter failed;

    public FillExpiringNotifier(FillSessionRepository sessionRepository, NotificationDispatcher dispatcher,
                                TransactionTemplate transaction, Clock clock, MeterRegistry registry) {
        this.sessionRepository = sessionRepository;
        this.dispatcher = dispatcher;
        this.transaction = transaction;
        this.clock = clock;
        this.notified = Counter.builder("fill.expiring.notified").description("만료 전날 안내를 보낸 세션 수").register(registry);
        this.failed = Counter.builder("fill.expiring.failed").description("만료 전날 안내에 실패한 세션 수").register(registry);
    }

    @Scheduled(cron = "${notify.fill-expiring.cron:0 0 0 * * *}")
    @SchedulerLock(name = "fillExpiringNotify", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        runOnce(LocalDateTime.now(clock));
    }

    /** 테스트와 수동 실행이 ShedLock 없이 부를 수 있게 분리했다. */
    public Result runOnce(LocalDateTime now) {
        // 1. 대상: (now, now+24h] 에 만료, 아직 안 보냄, 사진 있음
        List<FillSession> targets = sessionRepository.findExpiringTargets(now, now.plus(WINDOW));
        int sent = 0;
        int skipped = 0;
        int failures = 0;
        for (FillSession session : targets) {
            try {
                // 2. 보냈음 표시를 먼저 짧은 트랜잭션으로 — 겹쳐 돈 배치는 여기서 진다
                Integer claimed = transaction.execute(s -> sessionRepository.markExpiringNotified(session.getId(), now));
                if (claimed == null || claimed != 1) {
                    skipped++;
                    continue;
                }
                // 3. 알림함 저장 → 푸시 (트랜잭션 밖)
                dispatcher.notify(session.getOwner().getId(), message(session));
                sent++;
                notified.increment();
            } catch (RuntimeException e) {
                failures++;
                failed.increment();
                log.warn("Fill expiring notify failed (sessionId={}, cause={}: {})",
                        session.getId(), e.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.info("Fill expiring notify end (candidates={}, notified={}, skipped={}, failed={})",
                targets.size(), sent, skipped, failures);
        return new Result(targets.size(), sent, skipped, failures);
    }

    /** 제목이 없으면 코드로 부른다. */
    PushMessage message(FillSession session) {
        String label = session.getTitle() == null || session.getTitle().isBlank() ? session.getCode() : session.getTitle();
        return PushMessage.fillExpiring(session.getCode(), label);
    }
}
