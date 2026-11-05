package com.caestro.server.domain.b2b.service;

import com.caestro.server.domain.b2b.entity.B2bAssetDeletion.Kind;
import com.caestro.server.domain.b2b.repository.B2bAssetDeletionRepository;
import com.caestro.server.domain.catalog.store.CatalogAssetStore;
import com.caestro.server.domain.fill.store.FillImageStore;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 저장소 삭제는 프로젝트 DB 트랜잭션이 커밋된 뒤에만 실행한다. */
@Service
@RequiredArgsConstructor
public class B2bAssetDeletionWorker {
    private final B2bAssetDeletionRepository tasks;
    private final FillImageStore photos;
    private final CatalogAssetStore frames;
    private final Clock clock;

    /**
     * 작업 하나를 잠그고 멱등 삭제한다. 실패 시 작업 행을 남겨 다음 실행에서 재시도한다.
     * @param id 커밋된 삭제 작업 id
     * @throws RuntimeException 저장소 삭제 실패 또는 세션에 객체가 남은 경우
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(String id) {
        var task = tasks.findLockedById(id).orElse(null);
        if (task == null) return;
        if (task.getKind() == Kind.FILL_SESSION) {
            Long sessionId = Long.valueOf(task.getTargetId());
            photos.deleteBySession(sessionId);
            // S3 다중 삭제의 부분 실패나 한 페이지를 넘는 객체도 성공으로 처리하지 않는다.
            if (photos.countBySession(sessionId) != 0) {
                throw new IllegalStateException("B2B fill objects remain after deletion");
            }
        } else {
            frames.delete(task.getTargetId());
        }
        tasks.delete(task);
    }

    /** 실패한 트랜잭션과 분리해 재시도 시각을 기록한다. 이미 처리된 작업은 무시한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void defer(String id) {
        tasks.findLockedById(id).ifPresent(task -> task.deferUntil(LocalDateTime.now(clock).plusMinutes(5)));
    }
}
