package com.caestro.server.domain.b2b.service;

import com.caestro.server.domain.b2b.entity.B2bAssetDeletion;
import com.caestro.server.domain.b2b.entity.B2bAssetDeletion.Kind;
import com.caestro.server.domain.b2b.entity.B2bProject.Status;
import com.caestro.server.domain.b2b.repository.*;
import com.caestro.server.domain.fill.repository.*;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** B2B 관리자 삭제. B2C 서비스의 삭제 동작을 변경하지 않고 해당 프로젝트의 행만 제거한다. */
@Service
@RequiredArgsConstructor
public class B2bProjectDeletionService {
    private final B2bProjectRepository projects;
    private final B2bFrameRepository frames;
    private final B2bFillRepository fills;
    private final B2bImageRepository images;
    private final B2bAssetDeletionRepository tasks;
    private final FillSessionRepository sessions;
    private final FillSlotRepository slots;
    private final FillQueueRepository queue;
    private final FillImageRepository legacyPhotos;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * DRAFT/PAUSED 프로젝트와 네컷을 삭제하고 커밋 후 저장소 정리를 요청한다.
     * @param id 삭제할 프로젝트 id
     * @throws CustomException 404 - 없는 프로젝트, 409 - 공개 중인 프로젝트
     */
    @Transactional
    public void delete(String id) {
        // 1. 공개/재개/복제/생성과 같은 행을 잠가 상태 검증 이후 새 참조가 생기는 것을 막는다.
        var project = projects.findLockedById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.B2B_PROJECT_NOT_FOUND));
        if (project.getStatus() == Status.LIVE) throw new CustomException(ErrorCode.B2B_PROJECT_DELETE_NOT_ALLOWED);
        Set<String> imageIds = new TreeSet<>(frames.findImageIdsByProjectId(id));
        imageIds.addAll(fills.findImageIdsByProjectId(id));
        var ownedSessions = fills.findLockedSessionsByProjectId(id);
        List<String> taskIds = new ArrayList<>();

        // 2. FK 순서대로 DB 행만 삭제한다. S3 작업은 커밋 이전에 실행하지 않는다.
        fills.deleteByProjectId(id);
        for (var session : ownedSessions) {
            Long sessionId = session.getId();
            queue.deleteBySessionId(sessionId);
            slots.deleteBySessionId(sessionId);
            legacyPhotos.deleteBySessionId(sessionId);
            sessions.delete(session);
            taskIds.add(enqueue(Kind.FILL_SESSION, sessionId.toString()));
        }
        frames.deleteByProjectId(id);
        projects.delete(project);
        projects.flush();

        // 3. 현재 프레임과 과거 네컷 스냅샷 양쪽에서 해제된 사본만 원자적으로 제거한다.
        // 복제로 공유된 이미지와 카탈로그 업로드 원본에는 손대지 않는다.
        for (String imageId : imageIds) {
            if (images.deleteIfUnreferenced(imageId) == 1) taskIds.add(enqueue(Kind.FRAME_IMAGE, imageId));
        }
        events.publishEvent(new B2bAssetDeletionProcessor.Requested(List.copyOf(taskIds)));
    }

    private String enqueue(Kind kind, String targetId) {
        LocalDateTime now = LocalDateTime.now(clock);
        String id = kind.name() + ":" + targetId;
        tasks.save(B2bAssetDeletion.builder().id(id).kind(kind).targetId(targetId)
                .createdAt(now).nextAttemptAt(now).build());
        return id;
    }
}
