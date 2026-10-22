package com.caestro.server.domain.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.catalog.dto.request.UpsertPoseOverrideRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertPoseUploadRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseOverrideResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseUploadResponse;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.entity.CatalogPoseOverride;
import com.caestro.server.domain.catalog.entity.CatalogPoseUpload;
import com.caestro.server.domain.catalog.repository.CatalogBannerRepository;
import com.caestro.server.domain.catalog.repository.CatalogFrameRepository;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.domain.catalog.repository.CatalogPoseOverrideRepository;
import com.caestro.server.domain.catalog.repository.CatalogPoseUploadRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 포즈추천 카탈로그 규칙: 덧씌우기는 보낸 필드만 바꾸고 덧씌울 것이 없으면 행이 사라진다, 휴지통은 숨김+기한이고
 * 기한이 지나면 숨김으로 영구히 남는다(S3 는 못 지움), 업로드는 장소 필수·성별·계절은 허용값만,
 * GET /catalog/pose 는 숨김이거나 라벨이 있는 덧씌우기와 살아 있는 업로드만 내린다.
 */
@ExtendWith(MockitoExtension.class)
class CatalogPoseServiceTest {

    @Mock private CatalogPoseOverrideRepository overrideRepository;
    @Mock private CatalogPoseUploadRepository uploadRepository;
    @Mock private CatalogImageRepository imageRepository;
    @Mock private CatalogFrameRepository frameRepository;
    @Mock private CatalogBannerRepository bannerRepository;
    @Mock private CatalogIdGenerator idGenerator;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC);
    private CatalogPoseService service;
    private CatalogImage image;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        CatalogService catalogService = new CatalogService(frameRepository, bannerRepository, imageRepository, idGenerator, mapper, clock);
        ReflectionTestUtils.setField(catalogService, "publicBaseUrl", "https://api.peakpic.app");
        service = new CatalogPoseService(overrideRepository, uploadRepository, imageRepository, idGenerator, catalogService, mapper, clock);
        ReflectionTestUtils.setField(service, "trashDays", 7);
        image = CatalogImage.builder().id("img000000000000000000A").contentType("image/jpeg").data(new byte[10]).sizeBytes(10)
                .width(1024).height(1365).createdAt(LocalDateTime.now(clock)).build();
        lenient().when(overrideRepository.save(any(CatalogPoseOverride.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(uploadRepository.save(any(CatalogPoseUpload.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(idGenerator.poseUploadId()).thenReturn("pu_abcdefghij");
    }

    private CatalogPoseOverride override(Long id, boolean hidden, String labelsJson) {
        LocalDateTime t = LocalDateTime.now(clock).minusDays(1);
        return CatalogPoseOverride.builder().id(id).version("v012").shot("ext_beach_0001").hidden(hidden).labelsJson(labelsJson)
                .createdAt(t).updatedAt(t).build();
    }

    private CatalogPoseUpload upload(String id, boolean hidden) {
        LocalDateTime t = LocalDateTime.now(clock).minusDays(1);
        return CatalogPoseUpload.builder().id(id).image(image).width(1024).height(1365).labelsJson("{\"장소\":\"카페\"}")
                .sortOrder(0).hidden(hidden).createdAt(t).updatedAt(t).build();
    }

    // ── 덧씌우기 ──

    @Test
    @DisplayName("덧씌우기 만들기: 숨김과 라벨을 적고, 빈 라벨 값은 키를 뺀다")
    void upsertOverride_creates() {
        given(overrideRepository.findByVersionAndShot("v012", "ext_beach_0001")).willReturn(Optional.empty());

        CatalogPoseOverrideResponse res = service.upsertOverride("v012", "ext_beach_0001",
                new UpsertPoseOverrideRequest(true, Map.of("성별", "여자", "계절", "여름", "인종", " "), null));

        assertThat(res.hidden()).isTrue();
        assertThat(res.labels()).containsExactlyInAnyOrderEntriesOf(Map.of("성별", "여자", "계절", "여름"));
        assertThat(res.deletedAt()).isNull();
        assertThat(res.updatedAt()).isEqualTo(Instant.parse("2026-10-22T06:00:00Z"));
        verify(overrideRepository).save(any(CatalogPoseOverride.class));
    }

    @Test
    @DisplayName("보낸 필드만 바뀐다: hidden 만 보내면 라벨은 그대로, labels 만 보내면 숨김은 그대로")
    void upsertOverride_partial() {
        CatalogPoseOverride existing = override(1L, true, "{\"계절\":\"겨울\"}");
        given(overrideRepository.findByVersionAndShot("v012", "ext_beach_0001")).willReturn(Optional.of(existing));

        CatalogPoseOverrideResponse a = service.upsertOverride("v012", "ext_beach_0001", new UpsertPoseOverrideRequest(null, Map.of("성별", "남자"), null));
        assertThat(a.hidden()).isTrue();
        assertThat(a.labels()).containsExactlyEntriesOf(Map.of("성별", "남자"));   // 통째로 교체

        CatalogPoseOverrideResponse b = service.upsertOverride("v012", "ext_beach_0001", new UpsertPoseOverrideRequest(false, null, null));
        assertThat(b.hidden()).isFalse();
        assertThat(b.labels()).containsExactlyEntriesOf(Map.of("성별", "남자"));
    }

    @Test
    @DisplayName("덧씌울 것이 하나도 없어지면 행을 지운다")
    void upsertOverride_removesEmptyRow() {
        CatalogPoseOverride existing = override(1L, true, null);
        given(overrideRepository.findByVersionAndShot("v012", "ext_beach_0001")).willReturn(Optional.of(existing));

        CatalogPoseOverrideResponse res = service.upsertOverride("v012", "ext_beach_0001", new UpsertPoseOverrideRequest(false, Map.of(), null));

        assertThat(res.hidden()).isFalse();
        assertThat(res.labels()).isEmpty();
        verify(overrideRepository).delete(existing);
        verify(overrideRepository, never()).save(any(CatalogPoseOverride.class));
    }

    @Test
    @DisplayName("새 샷에 빈 값만 보내면 아무것도 저장하지 않는다")
    void upsertOverride_nothingToSaveForNewShot() {
        given(overrideRepository.findByVersionAndShot("v012", "ext_beach_0002")).willReturn(Optional.empty());

        service.upsertOverride("v012", "ext_beach_0002", new UpsertPoseOverrideRequest(false, null, null));

        verify(overrideRepository, never()).save(any(CatalogPoseOverride.class));
        verify(overrideRepository, never()).delete(any(CatalogPoseOverride.class));
    }

    @Test
    @DisplayName("덧씌우기 검증: 허용되지 않는 성별·계절·인종, 20자 넘는 값, 빈 version/shot 은 400")
    void upsertOverride_validation() {
        lenient().when(overrideRepository.findByVersionAndShot(any(), any())).thenReturn(Optional.empty());

        assertInvalidOverride("v012", "s", new UpsertPoseOverrideRequest(null, Map.of("성별", "기타"), null));
        assertInvalidOverride("v012", "s", new UpsertPoseOverrideRequest(null, Map.of("계절", "장마"), null));
        assertInvalidOverride("v012", "s", new UpsertPoseOverrideRequest(null, Map.of("인종", "x".repeat(21)), null));
        assertInvalidOverride("v012", "s", new UpsertPoseOverrideRequest(null, Map.of("인종", "남미"), null));
        assertInvalidOverride(" ", "s", new UpsertPoseOverrideRequest(true, null, null));
        assertInvalidOverride("v012", "", new UpsertPoseOverrideRequest(true, null, null));
        verify(overrideRepository, never()).save(any(CatalogPoseOverride.class));
    }

    private void assertInvalidOverride(String version, String shot, UpsertPoseOverrideRequest request) {
        assertThatThrownBy(() -> service.upsertOverride(version, shot, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_INVALID_POSE);
    }

    @Test
    @DisplayName("deleted=true 는 휴지통(숨김 + 7일 기한), 복구하면 다시 보이고 라벨은 남는다")
    void override_trashAndRestore() {
        CatalogPoseOverride existing = override(1L, false, "{\"계절\":\"여름\"}");
        given(overrideRepository.findByVersionAndShot("v012", "ext_beach_0001")).willReturn(Optional.of(existing));

        CatalogPoseOverrideResponse trashed = service.upsertOverride("v012", "ext_beach_0001", new UpsertPoseOverrideRequest(null, null, true));
        assertThat(trashed.hidden()).isTrue();
        assertThat(trashed.deletedAt()).isEqualTo(Instant.parse("2026-10-22T06:00:00Z"));
        assertThat(trashed.purgeAt()).isEqualTo(Instant.parse("2026-10-29T06:00:00Z"));
        given(overrideRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtDesc()).willReturn(List.of(existing));
        assertThat(service.trashedOverrides()).extracting(CatalogPoseOverrideResponse::shot).containsExactly("ext_beach_0001");

        CatalogPoseOverrideResponse restored = service.restoreOverride("v012", "ext_beach_0001");
        assertThat(restored.hidden()).isFalse();
        assertThat(restored.deletedAt()).isNull();
        assertThat(restored.labels()).containsExactlyEntriesOf(Map.of("계절", "여름"));
        verify(overrideRepository, never()).delete(existing);

        assertThatThrownBy(() -> service.restoreOverride("v012", "ext_beach_0001"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_NOT_IN_TRASH);
        given(overrideRepository.findByVersionAndShot("v999", "nope")).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.restoreOverride("v999", "nope"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_POSE_OVERRIDE_NOT_FOUND);
    }

    @Test
    @DisplayName("라벨 없는 샷을 휴지통에서 복구하면 덧씌울 것이 없어 행을 지운다")
    void override_restoreRemovesEmptyRow() {
        CatalogPoseOverride existing = override(1L, false, null);
        existing.moveToTrash(LocalDateTime.now(clock).minusDays(1), LocalDateTime.now(clock).plusDays(6));
        given(overrideRepository.findByVersionAndShot("v012", "ext_beach_0001")).willReturn(Optional.of(existing));

        CatalogPoseOverrideResponse res = service.restoreOverride("v012", "ext_beach_0001");

        assertThat(res.hidden()).isFalse();
        verify(overrideRepository).delete(existing);
    }

    @Test
    @DisplayName("기한 정리: 덧씌우기는 S3 를 못 지우니 숨김으로 영구히 남기고, 업로드는 그림과 함께 지운다")
    void purgeExpiredTrash() {
        LocalDateTime now = LocalDateTime.now(clock);
        CatalogPoseOverride expiredOverride = override(1L, false, null);
        expiredOverride.moveToTrash(now.minusDays(8), now.minusDays(1));
        CatalogPoseUpload expiredUpload = upload("pu_x", false);
        expiredUpload.moveToTrash(now.minusDays(8), now.minusDays(1));
        given(overrideRepository.findAllByPurgeAtBefore(now)).willReturn(List.of(expiredOverride));
        given(uploadRepository.findAllByPurgeAtBefore(now)).willReturn(List.of(expiredUpload));

        assertThat(service.purgeExpiredTrash(now)).isEqualTo(2);

        assertThat(expiredOverride.isHidden()).isTrue();
        assertThat(expiredOverride.isTrashed()).isFalse();
        assertThat(expiredOverride.getPurgeAt()).isNull();
        verify(overrideRepository, never()).delete(any(CatalogPoseOverride.class));
        verify(uploadRepository).delete(expiredUpload);
        verify(imageRepository).delete(image);
    }

    // ── 업로드 ──

    @Test
    @DisplayName("업로드 만들기: 장소 필수, 그림의 폭·높이를 가져오고 id 는 pu_ 로 시작한다")
    void createUpload_ok() {
        given(imageRepository.findById(image.getId())).willReturn(Optional.of(image));

        CatalogPoseUploadResponse res = service.createUpload(new UpsertPoseUploadRequest(image.getId(),
                Map.of("장소", " 카페 ", "성별", "남자", "인종", "동양", "계절", "봄·가을"), 3, null));

        assertThat(res.id()).isEqualTo("pu_abcdefghij");
        assertThat(res.labels()).containsExactlyInAnyOrderEntriesOf(Map.of("장소", "카페", "성별", "남자", "인종", "동양", "계절", "봄·가을"));
        assertThat(res.width()).isEqualTo(1024);
        assertThat(res.imageUrl()).isEqualTo("https://api.peakpic.app/catalog/images/" + image.getId());
        assertThat(res.sortOrder()).isEqualTo(3);
        assertThat(res.hidden()).isFalse();
        assertThat(res.deletedAt()).isNull();
    }

    @Test
    @DisplayName("업로드 검증: 장소 없음, 허용되지 않는 성별·계절, 없는 그림은 400")
    void createUpload_validation() {
        lenient().when(imageRepository.findById(image.getId())).thenReturn(Optional.of(image));

        assertInvalidUpload(new UpsertPoseUploadRequest(image.getId(), Map.of("성별", "남자"), null, null));
        assertInvalidUpload(new UpsertPoseUploadRequest(image.getId(), Map.of("장소", "카페", "성별", "중성"), null, null));
        assertInvalidUpload(new UpsertPoseUploadRequest(image.getId(), Map.of("장소", "카페", "계절", "가을"), null, null));
        assertInvalidUpload(new UpsertPoseUploadRequest("nope", Map.of("장소", "카페"), null, null));
        assertInvalidUpload(new UpsertPoseUploadRequest(image.getId(), null, null, null));
        verify(uploadRepository, never()).save(any(CatalogPoseUpload.class));
    }

    private void assertInvalidUpload(UpsertPoseUploadRequest request) {
        assertThatThrownBy(() -> service.createUpload(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_INVALID_POSE);
    }

    @Test
    @DisplayName("업로드 수정: 라벨은 통째로 교체, 그림을 바꾸면 이전 그림은 지운다; 삭제는 휴지통, 복구·완전 삭제")
    void upload_updateTrashRestorePurge() {
        CatalogPoseUpload u = upload("pu_a", false);
        CatalogImage replacement = CatalogImage.builder().id("img000000000000000000C").contentType("image/jpeg").data(new byte[1])
                .sizeBytes(1).width(800).height(1000).createdAt(LocalDateTime.now(clock)).build();
        given(uploadRepository.findById("pu_a")).willReturn(Optional.of(u));
        given(imageRepository.findById(replacement.getId())).willReturn(Optional.of(replacement));

        CatalogPoseUploadResponse edited = service.updateUpload("pu_a", new UpsertPoseUploadRequest(replacement.getId(),
                Map.of("장소", "해변", "계절", "여름"), null, true));
        assertThat(edited.labels()).containsExactlyInAnyOrderEntriesOf(Map.of("장소", "해변", "계절", "여름"));
        assertThat(edited.width()).isEqualTo(800);
        assertThat(edited.hidden()).isTrue();
        verify(imageRepository).delete(image);

        service.deleteUpload("pu_a");
        assertThat(u.isTrashed()).isTrue();
        assertThat(u.getPurgeAt()).isEqualTo(LocalDateTime.now(clock).plusDays(7));
        assertThatThrownBy(() -> service.updateUpload("pu_a", new UpsertPoseUploadRequest(null, null, 1, null)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_POSE_UPLOAD_NOT_FOUND);
        assertThatThrownBy(() -> service.deleteUpload("pu_a"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_POSE_UPLOAD_NOT_FOUND);

        assertThat(service.restoreUpload("pu_a").deletedAt()).isNull();
        assertThat(u.isTrashed()).isFalse();
        assertThatThrownBy(() -> service.purgeUpload("pu_a"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_NOT_IN_TRASH);

        service.deleteUpload("pu_a");
        service.purgeUpload("pu_a");
        verify(uploadRepository).delete(u);
        verify(imageRepository).delete(replacement);
    }

    // ── 읽기 ──

    @Test
    @DisplayName("GET /catalog/pose: 숨김이거나 라벨이 있는 덧씌우기만, 휴지통은 hidden=true 로; 업로드는 숨김 제외(all 이면 포함); updatedAt 은 가장 최근")
    void pose_shape() {
        CatalogPoseOverride hidden = override(1L, true, null);
        CatalogPoseOverride labelled = CatalogPoseOverride.builder().id(2L).version("v012").shot("ext_cafe_0002").hidden(false)
                .labelsJson("{\"계절\":\"겨울\"}").createdAt(LocalDateTime.now(clock)).updatedAt(LocalDateTime.now(clock)).build();
        CatalogPoseOverride trashed = CatalogPoseOverride.builder().id(3L).version("v012").shot("ext_cafe_0003").hidden(false)
                .createdAt(LocalDateTime.now(clock).minusDays(2)).updatedAt(LocalDateTime.now(clock).minusDays(2)).build();
        trashed.moveToTrash(LocalDateTime.now(clock).minusDays(2), LocalDateTime.now(clock).plusDays(5));
        CatalogPoseOverride empty = CatalogPoseOverride.builder().id(4L).version("v012").shot("ext_cafe_0004").hidden(false)
                .createdAt(LocalDateTime.now(clock).minusDays(3)).updatedAt(LocalDateTime.now(clock).minusDays(3)).build();
        given(overrideRepository.findAllByOrderByVersionAscShotAsc()).willReturn(List.of(hidden, labelled, trashed, empty));
        given(uploadRepository.findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc())
                .willReturn(List.of(upload("pu_a", false), upload("pu_b", true)));

        CatalogPoseResponse visible = service.pose(false);
        CatalogPoseResponse all = service.pose(true);

        assertThat(visible.overrides()).extracting(CatalogPoseOverrideResponse::shot)
                .containsExactly("ext_beach_0001", "ext_cafe_0002", "ext_cafe_0003");
        assertThat(visible.overrides().get(2).hidden()).isTrue();
        assertThat(visible.overrides().get(2).deletedAt()).isNotNull();
        assertThat(visible.overrides().get(1).labels()).containsExactlyEntriesOf(Map.of("계절", "겨울"));
        assertThat(visible.uploads()).extracting(CatalogPoseUploadResponse::id).containsExactly("pu_a");
        assertThat(all.uploads()).extracting(CatalogPoseUploadResponse::id).containsExactly("pu_a", "pu_b");
        assertThat(visible.updatedAt()).isEqualTo(Instant.parse("2026-10-22T06:00:00Z"));
    }

    @Test
    @DisplayName("빈 카탈로그의 updatedAt 은 null")
    void pose_empty() {
        given(overrideRepository.findAllByOrderByVersionAscShotAsc()).willReturn(List.of());
        given(uploadRepository.findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc()).willReturn(List.of());

        CatalogPoseResponse res = service.pose(false);

        assertThat(res.updatedAt()).isNull();
        assertThat(res.overrides()).isEmpty();
        assertThat(res.uploads()).isEmpty();
    }
}
