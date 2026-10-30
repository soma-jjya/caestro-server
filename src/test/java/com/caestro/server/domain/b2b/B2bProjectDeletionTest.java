package com.caestro.server.domain.b2b;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.caestro.server.domain.b2b.dto.request.*;
import com.caestro.server.domain.b2b.dto.response.*;
import com.caestro.server.domain.b2b.entity.*;
import com.caestro.server.domain.b2b.repository.*;
import com.caestro.server.domain.b2b.service.*;
import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.domain.catalog.store.CatalogAssetStore;
import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.entity.*;
import com.caestro.server.domain.fill.repository.*;
import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.domain.fill.store.FillImageStore;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"}, showSql = false)
@ContextConfiguration(classes = B2bPersistenceTest.Config.class)
@Import({B2bProjectDeletionService.class, B2bAssetDeletionWorker.class, B2bAssetDeletionProcessor.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class B2bProjectDeletionTest {
    @Autowired B2bProjectService projects;
    @Autowired B2bFillService b2b;
    @Autowired FillService fills;
    @Autowired B2bProjectDeletionService deletion;
    @Autowired B2bAssetDeletionProcessor processor;
    @Autowired B2bProjectRepository projectRows;
    @Autowired B2bFrameRepository frameRows;
    @Autowired B2bFillRepository b2bRows;
    @Autowired B2bImageRepository imageRows;
    @Autowired B2bAssetDeletionRepository tasks;
    @Autowired FillSessionRepository sessions;
    @Autowired FillSlotRepository slots;
    @Autowired FillQueueRepository queue;
    @Autowired FillImageRepository photos;
    @Autowired CatalogImageRepository catalog;
    @Autowired CatalogAssetStore assets;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    @MockitoBean FillImageStore photoStore;
    private Long userId;
    private String uploadedId;

    @BeforeEach
    void resetData() {
        tasks.deleteAllInBatch();
        b2bRows.deleteAllInBatch();
        queue.deleteAllInBatch();
        slots.deleteAllInBatch();
        photos.deleteAllInBatch();
        sessions.deleteAllInBatch();
        frameRows.deleteAllInBatch();
        projectRows.deleteAllInBatch();
        imageRows.deleteAllInBatch();
        catalog.deleteAllInBatch();
        users.deleteAllInBatch();
        userId = users.saveAndFlush(User.builder().deviceId("b2b-delete-test").build()).getId();
        uploadedId = UUID.randomUUID().toString().replace("-", "");
        catalog.saveAndFlush(CatalogImage.builder().id(uploadedId).contentType("image/png")
                .width(400).height(1200).sizeBytes(3).createdAt(LocalDateTime.now(clock)).build());
        assets.put(uploadedId, "image/png", new byte[]{1, 2, 3});
    }

    private B2bProjectResponse ready(String slug) {
        var project = projects.create(new UpsertB2bProjectRequest(slug, slug, ""));
        var windows = List.of(new FrameWindowDto(.1, .05, .8, .2, 0), new FrameWindowDto(.1, .28, .8, .2, 0),
                new FrameWindowDto(.1, .51, .8, .2, 0), new FrameWindowDto(.1, .74, .8, .2, 0));
        return projects.replaceFrames(project.id(), new ReplaceB2bFramesRequest(List.of(
                new ReplaceB2bFramesRequest.Frame(null, "프레임", B2bFrame.Layout.STRIP, uploadedId, windows))));
    }

    private B2bFillResponse create(B2bProjectResponse project) {
        var frame = project.frames().getFirst();
        return b2b.create(project.slug(), userId, new CreateB2bFillRequest(frame.id(), frame.version(), null));
    }

    private String imageId(B2bFrameResponse frame) {
        return frame.imageUrl().substring(frame.imageUrl().lastIndexOf('/') + 1);
    }

    private void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void draftDeletionRemovesFramesAndOwnedCopyButPreservesCatalogOriginal() {
        var draft = ready("draft");
        String imageId = imageId(draft.frames().getFirst());
        deletion.delete(draft.id());
        assertThat(projectRows.existsById(draft.id())).isFalse();
        assertThat(frameRows.findByProjectIdOrderByDisplayOrderAsc(draft.id())).isEmpty();
        assertThat(imageRows.existsById(imageId)).isFalse();
        error(() -> assets.load(imageId), ErrorCode.CATALOG_IMAGE_NOT_FOUND);
        assertThat(assets.load(uploadedId).data()).containsExactly(1, 2, 3);
        assertThat(catalog.existsById(uploadedId)).isTrue();
        assertThat(tasks.count()).isZero();
        error(() -> deletion.delete(draft.id()), ErrorCode.B2B_PROJECT_NOT_FOUND);
    }

    @Test
    void emptyDraftCanBeDeletedAndLiveOrMissingIsRejectedBeforeAnyStorageWork() {
        var draft = projects.create(new UpsertB2bProjectRequest("초안", "empty", ""));
        deletion.delete(draft.id());
        var live = projects.publish(ready("yuja").id());
        clearInvocations(photoStore);
        error(() -> deletion.delete(live.id()), ErrorCode.B2B_PROJECT_DELETE_NOT_ALLOWED);
        error(() -> deletion.delete("bp_missing"), ErrorCode.B2B_PROJECT_NOT_FOUND);
        assertThat(projects.get(live.id())).isEqualTo(live);
        assertThat(tasks.count()).isZero();
        verifyNoInteractions(photoStore);
    }

    @Test
    void pausedDeletionRemovesOldSnapshotAndAllFillRowsWhileYujaAndB2cSurvive() {
        var target = projects.publish(ready("test-project").id());
        var oldFill = create(target);
        var session = sessions.findByCode(oldFill.fill().code()).orElseThrow();
        String photoId = "photo-delete-test";
        photos.saveAndFlush(FillImage.builder().id(photoId).session(session).contentType("image/png")
                .data(new byte[]{4}).sizeBytes(1).build());
        queue.saveAndFlush(FillQueueItem.builder().session(session).imageId(photoId).fromSlot(0)
                .queuedAt(LocalDateTime.now(clock)).build());
        projects.pause(target.id());
        // 프레임 목록에서 빠져도 네컷 스냅샷이 보존하던 사본까지 정리해야 한다.
        projects.replaceFrames(target.id(), new ReplaceB2bFramesRequest(List.of()));
        var yuja = projects.publish(ready("yuja").id());
        var realFill = create(yuja);
        var b2c = fills.create(userId, new CreateFillRequest("White", "Strip", null, null, null, false));
        clearInvocations(photoStore);

        deletion.delete(target.id());

        assertThat(sessions.existsById(session.getId())).isFalse();
        assertThat(slots.findBySessionIdOrderBySlotIndex(session.getId())).isEmpty();
        assertThat(queue.findBySessionIdOrderByIdAsc(session.getId())).isEmpty();
        assertThat(photos.countBySessionId(session.getId())).isZero();
        assertThat(b2bRows.findByProjectSlugAndSessionCode(target.slug(), oldFill.fill().code())).isEmpty();
        assertThat(imageRows.existsById(imageId(oldFill.frame()))).isFalse();
        error(() -> fills.get(oldFill.fill().code(), userId), ErrorCode.FILL_NOT_FOUND);
        assertThat(projects.get(yuja.id())).isEqualTo(yuja);
        assertThat(b2b.get("yuja", realFill.fill().code(), userId).frame()).isEqualTo(realFill.frame());
        assertThat(fills.get(b2c.code(), userId).code()).isEqualTo(b2c.code());
        verify(photoStore).deleteBySession(session.getId());
        assertThat(tasks.count()).isZero();
    }

    @Test
    void sharedFrameAndOtherProjectsSnapshotAreBothProtectedUntilLastReferenceIsDeleted() {
        var original = ready("original");
        var copy = projects.duplicate(original.id(), new DuplicateB2bProjectRequest("복제", "copy"));
        String shared = imageId(original.frames().getFirst());
        deletion.delete(original.id());
        assertThat(imageRows.existsById(shared)).isTrue();
        assertThat(assets.load(shared).data()).containsExactly(1, 2, 3);
        var third = projects.duplicate(copy.id(), new DuplicateB2bProjectRequest("세 번째", "third"));
        projects.publish(copy.id());
        var retained = create(copy);
        projects.pause(copy.id());
        projects.replaceFrames(copy.id(), new ReplaceB2bFramesRequest(List.of()));

        deletion.delete(third.id());
        assertThat(imageRows.existsById(shared)).isTrue();
        assertThat(b2b.get("copy", retained.fill().code(), userId).frame()).isEqualTo(retained.frame());
        deletion.delete(copy.id());
        assertThat(imageRows.existsById(shared)).isFalse();
        error(() -> assets.load(shared), ErrorCode.CATALOG_IMAGE_NOT_FOUND);
    }

    @Test
    void rollbackRestoresDatabaseAndNeverDeletesStoredObjects() {
        var target = projects.publish(ready("rollback").id());
        var fill = create(target);
        projects.pause(target.id());
        clearInvocations(photoStore);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            deletion.delete(target.id());
            assertThat(tasks.count()).isPositive();
            verifyNoInteractions(photoStore);
            tx.setRollbackOnly();
        });
        assertThat(projectRows.existsById(target.id())).isTrue();
        assertThat(b2b.get("rollback", fill.fill().code(), userId).frame()).isEqualTo(fill.frame());
        assertThat(assets.load(imageId(fill.frame())).data()).containsExactly(1, 2, 3);
        assertThat(tasks.count()).isZero();
        verifyNoInteractions(photoStore);
    }

    @Test
    void storageFailureKeepsDurableTaskAndRetryCompletesIt() {
        var target = projects.publish(ready("retry-test").id());
        var fill = create(target);
        Long sessionId = sessions.findByCode(fill.fill().code()).orElseThrow().getId();
        projects.pause(target.id());
        doThrow(new IllegalStateException("storage unavailable")).doNothing().when(photoStore).deleteBySession(sessionId);

        deletion.delete(target.id());

        assertThat(projectRows.existsById(target.id())).isFalse();
        assertThat(tasks.findAll()).singleElement().satisfies(task -> {
            assertThat(task.getTargetId()).isEqualTo(sessionId.toString());
            assertThat(task.getNextAttemptAt()).isEqualTo(LocalDateTime.now(clock).plusMinutes(5));
        });
        makePendingTasksDue();
        processor.retryPending();
        assertThat(tasks.count()).isZero();
        verify(photoStore, times(2)).deleteBySession(sessionId);
    }

    @Test
    void partialObjectDeletionIsRetriedInsteadOfLosingTheTask() {
        var target = projects.publish(ready("partial").id());
        var fill = create(target);
        Long sessionId = sessions.findByCode(fill.fill().code()).orElseThrow().getId();
        projects.pause(target.id());
        when(photoStore.countBySession(sessionId)).thenReturn(1L, 0L);
        deletion.delete(target.id());
        assertThat(tasks.count()).isEqualTo(1);
        makePendingTasksDue();
        processor.retryPending();
        assertThat(tasks.count()).isZero();
        verify(photoStore, times(2)).deleteBySession(sessionId);
    }

    private void makePendingTasksDue() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> tasks.findAll()
                .forEach(task -> task.deferUntil(LocalDateTime.now(clock))));
    }
}
