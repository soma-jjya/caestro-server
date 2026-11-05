package com.caestro.server.domain.b2b.service;

import com.caestro.server.domain.b2b.entity.B2bAssetDeletion;
import com.caestro.server.domain.b2b.repository.B2bAssetDeletionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 커밋 직후 정리하고, 프로세스 종료/저장소 오류로 남은 작업은 주기적으로 재처리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class B2bAssetDeletionProcessor {
    public record Requested(List<String> taskIds) {}
    private final B2bAssetDeletionRepository tasks;
    private final B2bAssetDeletionWorker worker;
    private final Clock clock;

    /** DB 롤백 시에는 호출하지 않는다. 저장소 실패는 영속 작업으로 재시도한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(Requested event) { process(event.taskIds()); }

    /** 한 번에 최대 100개를 처리하며 실패 작업은 5분 뒤 재시도한다. */
    @Scheduled(fixedDelayString = "${cleanup.b2b.asset-delete-delay-ms:60000}")
    @SchedulerLock(name = "b2bAssetDeletion", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void retryPending() {
        process(tasks.findTop100ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(LocalDateTime.now(clock))
                .stream().map(B2bAssetDeletion::getId).toList());
    }

    private void process(List<String> ids) {
        for (String id : ids) {
            try {
                worker.process(id);
            } catch (RuntimeException failure) {
                log.warn("B2B asset deletion pending (taskId={}, cause={})", id, failure.getClass().getSimpleName());
                try {
                    worker.defer(id);
                } catch (RuntimeException retryFailure) {
                    // DB 장애여도 기존 작업은 남아 있어 다음 배치에서 다시 읽는다.
                    log.warn("B2B asset deletion retry scheduling failed (taskId={}, cause={})",
                            id, retryFailure.getClass().getSimpleName());
                }
            }
        }
    }
}
