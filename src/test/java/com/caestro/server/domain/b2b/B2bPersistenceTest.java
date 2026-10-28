package com.caestro.server.domain.b2b;

import static org.assertj.core.api.Assertions.*;

import com.caestro.server.domain.b2b.dto.request.*;
import com.caestro.server.domain.b2b.dto.response.*;
import com.caestro.server.domain.b2b.entity.*;
import com.caestro.server.domain.b2b.repository.*;
import com.caestro.server.domain.b2b.service.*;
import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.repository.CatalogFrameRepository;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.domain.catalog.store.CatalogAssetStore;
import com.caestro.server.domain.catalog.store.RdsCatalogAssetStore;
import com.caestro.server.domain.fill.dto.request.UpdateFrameRequest;
import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.repository.*;
import com.caestro.server.domain.fill.service.FillCodeGenerator;
import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.domain.fill.store.FillImageStore;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 실제 트랜잭션·유일 제약·FK로 원자성/스냅샷 수명을 검증한다. 네트워크/외부 DB는 필요 없다. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"}, showSql = false)
@ContextConfiguration(classes = B2bPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class B2bPersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EntityScan(basePackageClasses = {B2bProject.class, FillSession.class, User.class, CatalogImage.class})
    @EnableJpaRepositories(basePackageClasses = {B2bProjectRepository.class, FillSessionRepository.class,
            UserRepository.class, CatalogImageRepository.class})
    @Import({B2bProjectService.class, B2bFillService.class, B2bImageService.class, B2bFrameMapper.class,
            FillService.class, FillCodeGenerator.class, RdsCatalogAssetStore.class})
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    private static final List<FrameWindowDto> WINDOWS = List.of(
            new FrameWindowDto(.1, .05, .8, .2, 0), new FrameWindowDto(.1, .28, .8, .2, 0),
            new FrameWindowDto(.1, .51, .8, .2, 0), new FrameWindowDto(.1, .74, .8, .2, 0));
    @Autowired B2bProjectService projects;
    @Autowired B2bFillService b2b;
    @Autowired B2bImageService images;
    @Autowired FillService fills;
    @Autowired B2bProjectRepository projectRows;
    @Autowired B2bFrameRepository frameRows;
    @Autowired B2bImageRepository imageRows;
    @Autowired B2bFillRepository b2bRows;
    @Autowired FillSessionRepository sessionRows;
    @Autowired FillSlotRepository slotRows;
    @Autowired FillQueueRepository queueRows;
    @Autowired CatalogImageRepository catalogImages;
    @Autowired CatalogAssetStore store;
    @Autowired CatalogFrameRepository catalogFrames;
    @Autowired UserRepository users;
    @MockitoBean FillImageStore imageStore;
    private Long userId;
    private String uploadedId;

    @BeforeEach
    void reset() {
        b2bRows.deleteAllInBatch();
        queueRows.deleteAllInBatch();
        slotRows.deleteAllInBatch();
        sessionRows.deleteAllInBatch();
        frameRows.deleteAllInBatch();
        projectRows.deleteAllInBatch();
        imageRows.deleteAllInBatch();
        catalogFrames.deleteAllInBatch();
        catalogImages.deleteAllInBatch();
        users.deleteAllInBatch();
        userId = users.saveAndFlush(User.builder().deviceId("b2b-test").build()).getId();
        uploadedId = upload("image/png");
    }

    private String upload(String type) {
        String id = UUID.randomUUID().toString().replace("-", "");
        catalogImages.saveAndFlush(CatalogImage.builder().id(id).contentType(type)
                .sizeBytes(3).width(400).height(1200).createdAt(LocalDateTime.of(2026, 10, 20, 0, 0)).build());
        store.put(id, type, new byte[]{1, 2, 3});
        return id;
    }

    private ReplaceB2bFramesRequest.Frame input(String id, String name, String imageId) {
        return new ReplaceB2bFramesRequest.Frame(id, name, B2bFrame.Layout.STRIP, imageId, WINDOWS);
    }

    private B2bProjectResponse ready(String slug) {
        return ready(slug, 3);
    }

    private B2bProjectResponse ready(String slug, int count) {
        var p = projects.create(new UpsertB2bProjectRequest("유자 축제", slug, "함께 네컷"));
        List<ReplaceB2bFramesRequest.Frame> choices = new ArrayList<>();
        for (int i = 0; i < count; i++) choices.add(input(null, "유자 " + (i + 1), uploadedId));
        return projects.replaceFrames(p.id(), new ReplaceB2bFramesRequest(choices));
    }

    private CreateB2bFillRequest choose(B2bFrameResponse frame) {
        return new CreateB2bFillRequest(frame.id(), frame.version(), "우리 네컷");
    }

    private void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void publishPauseResumeAndImmutableSlug() {
        var draft = projects.create(new UpsertB2bProjectRequest("초안", "draft", null));
        assertThat(draft.status()).isEqualTo(B2bProject.Status.DRAFT);
        assertThat(draft.frames()).isEmpty();
        error(() -> projects.publish(draft.id()), ErrorCode.B2B_NOT_PUBLISHABLE);
        error(() -> projects.publicProject("draft"), ErrorCode.B2B_PROJECT_NOT_FOUND);

        var ready = ready("yuja");
        var live = projects.publish(ready.id());
        assertThat(projects.publicProject("yuja").frames()).hasSize(3);
        projects.pause(live.id());
        error(() -> projects.publicProject("yuja"), ErrorCode.B2B_PROJECT_NOT_FOUND);
        error(() -> projects.update(live.id(), new UpsertB2bProjectRequest("수정", "other", "")), ErrorCode.B2B_SLUG_LOCKED);
        assertThat(projects.resume(live.id()).publishedAt()).isEqualTo(live.publishedAt());
        error(() -> projects.publish(live.id()), ErrorCode.B2B_INVALID_TRANSITION);
        assertThat(projects.update(live.id(), new UpsertB2bProjectRequest("매장", "yuja", "변경")).name()).isEqualTo("매장");
        assertThat(catalogFrames.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void oneToThreeFramesCanPublishResumeAndCreateSessions(int count) {
        var live = projects.publish(ready("yuja", count).id());
        assertThat(live.status()).isEqualTo(B2bProject.Status.LIVE);
        assertThat(live.publishedAt()).isNotNull();
        var choices = projects.publicProject("yuja").frames();
        assertThat(choices).hasSize(count).containsExactlyElementsOf(live.frames());
        for (var frame : choices) {
            var created = b2b.create("yuja", userId, choose(frame));
            assertThat(created.frame()).isEqualTo(frame);
            assertThat(created.fill().slots()).hasSize(4);
            assertThat(b2b.get("yuja", created.fill().code(), userId).frame()).isEqualTo(frame);
        }

        projects.pause(live.id());
        error(() -> projects.publicProject("yuja"), ErrorCode.B2B_PROJECT_NOT_FOUND);
        var resumed = projects.resume(live.id());
        assertThat(resumed.status()).isEqualTo(B2bProject.Status.LIVE);
        assertThat(resumed.publishedAt()).isEqualTo(live.publishedAt());
        assertThat(projects.publicProject("yuja").frames()).containsExactlyElementsOf(choices);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void liveReplacementAllowsOneToThreeFramesAndPreservesExistingSnapshot(int count) {
        var live = projects.publish(ready("yuja").id());
        var previous = b2b.create("yuja", userId, choose(live.frames().getLast()));
        var inputs = live.frames().subList(0, count).stream()
                .map(f -> input(f.id(), f.name(), uploadedId)).toList();

        var replaced = projects.replaceFrames(live.id(), new ReplaceB2bFramesRequest(inputs));
        assertThat(replaced.status()).isEqualTo(B2bProject.Status.LIVE);
        assertThat(replaced.frames()).hasSize(count);
        assertThat(projects.publicProject("yuja").frames()).containsExactlyElementsOf(replaced.frames());
        assertThat(b2b.get("yuja", previous.fill().code(), userId).frame()).isEqualTo(previous.frame());
        for (var frame : replaced.frames()) {
            assertThat(b2b.create("yuja", userId, choose(frame)).frame()).isEqualTo(frame);
        }
        if (count < 3) {
            error(() -> b2b.create("yuja", userId, choose(previous.frame())), ErrorCode.B2B_FRAME_NOT_FOUND);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void draftAndPausedCanKeepZeroToThreeIncompleteFramesButCannotGoLive(int count) {
        List<ReplaceB2bFramesRequest.Frame> incomplete = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            incomplete.add(new ReplaceB2bFramesRequest.Frame(null, "준비 중 " + i,
                    B2bFrame.Layout.STRIP, null, List.of()));
        }
        var request = new ReplaceB2bFramesRequest(incomplete);
        var draft = projects.create(new UpsertB2bProjectRequest("준비", "draft", null));
        assertThat(projects.replaceFrames(draft.id(), request).frames()).hasSize(count);
        error(() -> projects.publish(draft.id()), ErrorCode.B2B_NOT_PUBLISHABLE);
        assertThat(projects.get(draft.id()).status()).isEqualTo(B2bProject.Status.DRAFT);
        assertThat(projects.get(draft.id()).publishedAt()).isNull();

        var live = projects.publish(ready("yuja").id());
        projects.pause(live.id());
        assertThat(projects.replaceFrames(live.id(), request).frames()).hasSize(count);
        error(() -> projects.resume(live.id()), ErrorCode.B2B_NOT_PUBLISHABLE);
        assertThat(projects.get(live.id()).status()).isEqualTo(B2bProject.Status.PAUSED);
        assertThat(projects.get(live.id()).publishedAt()).isEqualTo(live.publishedAt());
    }

    @ParameterizedTest
    @CsvSource({"false,4", "true,0", "true,1", "true,2", "true,3"})
    void incompleteFrameMixedWithValidFrameStillBlocksPublicationAndLiveReplacement(boolean hasImage, int windows) {
        var incomplete = new ReplaceB2bFramesRequest.Frame(null, "미완성", B2bFrame.Layout.STRIP,
                hasImage ? uploadedId : null, WINDOWS.subList(0, windows));
        var request = new ReplaceB2bFramesRequest(List.of(input(null, "완성", uploadedId), incomplete));
        var draft = projects.create(new UpsertB2bProjectRequest("준비", "draft", null));
        projects.replaceFrames(draft.id(), request);
        error(() -> projects.publish(draft.id()), ErrorCode.B2B_NOT_PUBLISHABLE);
        assertThat(projects.get(draft.id()).status()).isEqualTo(B2bProject.Status.DRAFT);

        var live = projects.publish(ready("yuja").id());
        long imageCount = imageRows.count();
        error(() -> projects.replaceFrames(live.id(), request), ErrorCode.B2B_NOT_PUBLISHABLE);
        assertThat(projects.get(live.id()).frames()).isEqualTo(live.frames());
        assertThat(projects.get(live.id()).status()).isEqualTo(B2bProject.Status.LIVE);
        assertThat(imageRows.count()).isEqualTo(imageCount);

        projects.pause(live.id());
        projects.replaceFrames(live.id(), request);
        error(() -> projects.resume(live.id()), ErrorCode.B2B_NOT_PUBLISHABLE);
        assertThat(projects.get(live.id()).status()).isEqualTo(B2bProject.Status.PAUSED);
        assertThat(projects.get(live.id()).publishedAt()).isEqualTo(live.publishedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "LIVE", "PAUSED"})
    void moreThanThreeFramesAreRejectedWithoutChangingExistingFrames(String status) {
        var project = ready("yuja");
        if (!status.equals("DRAFT")) projects.publish(project.id());
        if (status.equals("PAUSED")) projects.pause(project.id());
        var request = new ReplaceB2bFramesRequest(List.of(input(null, "1", uploadedId),
                input(null, "2", uploadedId), input(null, "3", uploadedId), input(null, "4", uploadedId)));

        error(() -> projects.replaceFrames(project.id(), request), ErrorCode.B2B_INVALID_FRAME);
        var unchanged = projects.get(project.id());
        assertThat(unchanged.status()).isEqualTo(B2bProject.Status.valueOf(status));
        assertThat(unchanged.frames()).isEqualTo(project.frames());
    }

    @Test
    void replacementIsAtomicEvenWhenLaterImageFails() {
        var p = ready("yuja");
        var before = p.frames();
        long imageCount = imageRows.count();
        String replacementImage = upload("image/jpeg");
        error(() -> projects.replaceFrames(p.id(), new ReplaceB2bFramesRequest(List.of(
                input(before.get(0).id(), "바뀌면 안 됨", replacementImage),
                input(before.get(1).id(), "실패", "missing")))), ErrorCode.B2B_INVALID_IMAGE);
        assertThat(projects.get(p.id()).frames()).isEqualTo(before);
        assertThat(imageRows.count()).isEqualTo(imageCount);
    }

    @Test
    void replacementRejectsForeignAndDuplicateIdsAndKeepsLiveValid() {
        var p = ready("yuja");
        var other = ready("store");
        String id = p.frames().getFirst().id();
        error(() -> projects.replaceFrames(p.id(), new ReplaceB2bFramesRequest(List.of(
                input(id, "a", uploadedId), input(id, "b", uploadedId)))), ErrorCode.B2B_INVALID_FRAME);
        error(() -> projects.replaceFrames(p.id(), new ReplaceB2bFramesRequest(List.of(
                input(other.frames().getFirst().id(), "외부", uploadedId)))), ErrorCode.B2B_INVALID_FRAME);
        projects.publish(p.id());
        error(() -> projects.replaceFrames(p.id(), new ReplaceB2bFramesRequest(List.of())), ErrorCode.B2B_NOT_PUBLISHABLE);
        assertThat(projects.get(p.id()).frames()).isEqualTo(p.frames());
    }

    @Test
    void orderAndVersionsChangeOnlyWhenConfigurationChanges() {
        var p = ready("yuja");
        var original = p.frames();
        var unchanged = projects.replaceFrames(p.id(), new ReplaceB2bFramesRequest(original.stream()
                .map(f -> input(f.id(), f.name(), uploadedId)).toList()));
        assertThat(unchanged.frames()).isEqualTo(original);
        var reversed = new ArrayList<>(original.reversed());
        var updated = projects.replaceFrames(p.id(), new ReplaceB2bFramesRequest(reversed.stream()
                .map(f -> input(f.id(), f.name(), uploadedId)).toList()));
        assertThat(updated.frames()).extracting(B2bFrameResponse::id).containsExactlyElementsOf(reversed.stream().map(B2bFrameResponse::id).toList());
        assertThat(updated.frames()).extracting(B2bFrameResponse::displayOrder).containsExactly(0, 1, 2);
        assertThat(updated.frames()).extracting(B2bFrameResponse::version).containsExactly(2, 1, 2);
    }

    @Test
    void snapshotSurvivesReplacementCatalogDeletionAndPause() {
        var p = projects.publish(ready("yuja").id());
        var first = b2b.create("yuja", userId, choose(p.frames().getFirst()));
        assertThat(first.shareUrl()).isEqualTo("https://peakpic.app/go/yuja/" + first.fill().code());
        assertThat(first.fill().slots()).hasSize(4);
        assertThat(first.fill().expiresAt()).isEqualTo(Instant.parse("2026-10-29T12:00:00Z"));
        catalogImages.deleteById(uploadedId);
        // 같은 업로드 id를 다시 보내도 카탈로그 원본은 필요 없다.
        projects.replaceFrames(p.id(), new ReplaceB2bFramesRequest(p.frames().stream()
                .map(f -> input(f.id(), f.name(), uploadedId)).toList()));
        projects.pause(p.id());
        projects.replaceFrames(p.id(), new ReplaceB2bFramesRequest(List.of()));
        images.purgeUnreferenced(LocalDateTime.of(2099, 1, 1, 0, 0));
        var resumed = b2b.get("yuja", first.fill().code().toLowerCase(Locale.ROOT), userId);
        assertThat(resumed.frame()).isEqualTo(first.frame());
        assertThat(imageRows.count()).isEqualTo(1);
        assertThat(images.image(first.frame().imageUrl().substring(first.frame().imageUrl().lastIndexOf('/') + 1)).data())
                .containsExactly(1, 2, 3);
        error(() -> b2b.create("yuja", userId, choose(p.frames().getFirst())), ErrorCode.B2B_PROJECT_NOT_FOUND);
        error(() -> b2b.get("store", first.fill().code(), userId), ErrorCode.B2B_FILL_NOT_FOUND);
        error(() -> fills.updateFrame(first.fill().code(), userId, new UpdateFrameRequest("Black", "Grid", null, null, false)),
                ErrorCode.FILL_FRAME_LOCKED);
        error(() -> fills.uploadFrameAsset(first.fill().code(), 0, userId, new byte[]{1}, "image/png"), ErrorCode.FILL_FRAME_LOCKED);
    }

    @Test
    void staleOrForeignFrameCannotCreateSession() {
        var p = projects.publish(ready("yuja").id());
        var old = p.frames().getFirst();
        error(() -> b2b.create("yuja", userId, new CreateB2bFillRequest("foreign", 1, null)), ErrorCode.B2B_FRAME_NOT_FOUND);
        error(() -> b2b.create("yuja", userId, new CreateB2bFillRequest(old.id(), old.version() + 1, null)), ErrorCode.B2B_FRAME_VERSION_CHANGED);
        assertThat(sessionRows.count()).isZero();
    }

    @Test
    void duplicateCopiesOnlySettingsWithNewIdentity() {
        var p = projects.publish(ready("yuja").id());
        b2b.create("yuja", userId, choose(p.frames().getFirst()));
        var copy = projects.duplicate(p.id(), new DuplicateB2bProjectRequest("매장", "store"));
        assertThat(copy.status()).isEqualTo(B2bProject.Status.DRAFT);
        assertThat(copy.publishedAt()).isNull();
        assertThat(copy.frames()).hasSize(3).allMatch(f -> f.version() == 1);
        assertThat(copy.frames()).extracting(B2bFrameResponse::id).doesNotContainAnyElementsOf(p.frames().stream().map(B2bFrameResponse::id).toList());
        assertThat(copy.frames()).extracting(B2bFrameResponse::imageUrl).containsExactlyElementsOf(p.frames().stream().map(B2bFrameResponse::imageUrl).toList());
        assertThat(b2bRows.count()).isEqualTo(1);
        assertThat(sessionRows.count()).isEqualTo(1);
    }

    @Test
    void existingCleanupCascadesB2bAssociationAndReleasesLastImage() {
        var p = projects.publish(ready("yuja").id());
        var fill = b2b.create("yuja", userId, choose(p.frames().getFirst()));
        projects.pause(p.id());
        projects.replaceFrames(p.id(), new ReplaceB2bFramesRequest(List.of()));
        Long sessionId = sessionRows.findByCode(fill.fill().code()).orElseThrow().getId();
        fills.purgeSession(sessionId);
        assertThat(b2bRows.count()).isZero();
        assertThat(slotRows.count()).isZero();
        images.purgeUnreferenced(LocalDateTime.of(2099, 1, 1, 0, 0));
        assertThat(imageRows.count()).isZero();
    }

    @Test
    void videoCannotBeUsedAsFrame() {
        var p = projects.create(new UpsertB2bProjectRequest("초안", "draft", null));
        String video = upload("video/mp4");
        error(() -> projects.replaceFrames(p.id(), new ReplaceB2bFramesRequest(List.of(input(null, "동영상", video)))), ErrorCode.B2B_INVALID_IMAGE);
        assertThat(projects.get(p.id()).frames()).isEmpty();
    }

    @Test
    void simultaneousSlugCreationProducesOneProjectAndOneConflict() throws Exception {
        var barrier = new CyclicBarrier(2);
        Callable<String> create = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return projects.create(new UpsertB2bProjectRequest("축제", "same-slug", "")).slug();
            } catch (CustomException e) {
                return e.getErrorCode().name();
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(create);
            var b = executor.submit(create);
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("same-slug", "B2B_SLUG_DUPLICATE");
        }
        assertThat(projectRows.count()).isEqualTo(1);
        assertThat(projects.slugCheck("same-slug", null).reason()).isEqualTo("duplicate");
        assertThat(projects.slugCheck("same-slug", projects.list().getFirst().id()).available()).isTrue();
    }
}
