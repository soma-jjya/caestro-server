package com.caestro.server.domain.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.dto.LocalizedText;
import com.caestro.server.domain.catalog.dto.request.UpsertBannerRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertFrameRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogBannerResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogImageResponse;
import com.caestro.server.domain.catalog.entity.CatalogBanner;
import com.caestro.server.domain.catalog.entity.CatalogFrame;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.repository.CatalogBannerRepository;
import com.caestro.server.domain.catalog.repository.CatalogFrameRepository;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 카탈로그 규칙: 그림은 PNG/JPEG 만, 프레임은 창 4개가 이미지 안에, 숨김은 목록에서만 빠지고 id 로는 열린다,
 * 그림을 바꾸면 이전 그림도 지운다, 지우기는 휴지통(7일)으로 가고 복구·완전 삭제·기한 정리가 따로 있다,
 * 홈 배너는 최소 한 개는 보여야 한다, 저장되지 않은 그림은 정리된다.
 */
@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock private CatalogFrameRepository frameRepository;
    @Mock private CatalogBannerRepository bannerRepository;
    @Mock private CatalogImageRepository imageRepository;
    @Mock private CatalogIdGenerator idGenerator;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC);
    private CatalogService service;
    private CatalogImage image;

    private static final List<FrameWindowDto> WINDOWS = List.of(
            new FrameWindowDto(0.05, 0.02, 0.43, 0.38, 0),
            new FrameWindowDto(0.51, 0.02, 0.43, 0.38, 5),
            new FrameWindowDto(0.05, 0.43, 0.43, 0.41, 0),
            new FrameWindowDto(0.51, 0.43, 0.43, 0.41, -5));

    @BeforeEach
    void setUp() {
        service = new CatalogService(frameRepository, bannerRepository, imageRepository, idGenerator, new ObjectMapper(), clock);
        ReflectionTestUtils.setField(service, "maxImageBytes", 8L * 1024 * 1024);
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://api.peakpic.app");
        ReflectionTestUtils.setField(service, "trashDays", 7);
        image = CatalogImage.builder().id("img000000000000000000A").contentType("image/png").data(new byte[10]).sizeBytes(10)
                .width(1062).height(1481).createdAt(LocalDateTime.now(clock)).build();
        lenient().when(frameRepository.save(any(CatalogFrame.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bannerRepository.save(any(CatalogBanner.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(imageRepository.save(any(CatalogImage.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(idGenerator.frameId()).thenReturn("af_abcdefghij");
        lenient().when(idGenerator.bannerId()).thenReturn("hb_abcdefghij");
        lenient().when(idGenerator.imageId()).thenReturn("img000000000000000000B");
    }

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    private CatalogFrame frame(String id, boolean hidden, int sortOrder, LocalDateTime updatedAt) {
        return CatalogFrame.builder().id(id).titleKo("제목").titleEn("Title").image(image).width(1062).height(1481)
                .windowsJson("[{\"x\":0.05,\"y\":0.02,\"w\":0.43,\"h\":0.38,\"rot\":0},{\"x\":0.51,\"y\":0.02,\"w\":0.43,\"h\":0.38,\"rot\":5},"
                        + "{\"x\":0.05,\"y\":0.43,\"w\":0.43,\"h\":0.41,\"rot\":0},{\"x\":0.51,\"y\":0.43,\"w\":0.43,\"h\":0.41,\"rot\":-5}]")
                .sortOrder(sortOrder).hidden(hidden).createdAt(updatedAt).updatedAt(updatedAt).build();
    }

    // ── 그림 ──

    @Test
    @DisplayName("PNG 를 올리면 폭·높이를 읽어 저장하고 주소를 돌려준다")
    void storeImage_readsDimensions() throws IOException {
        CatalogImageResponse res = service.storeImage(png(300, 400), "image/png");

        assertThat(res.width()).isEqualTo(300);
        assertThat(res.height()).isEqualTo(400);
        assertThat(res.url()).isEqualTo("https://api.peakpic.app/catalog/images/img000000000000000000B");
        ArgumentCaptor<CatalogImage> saved = ArgumentCaptor.forClass(CatalogImage.class);
        verify(imageRepository).save(saved.capture());
        assertThat(saved.getValue().getContentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("PNG/JPEG 가 아니거나 읽을 수 없는 바이트는 400")
    void storeImage_rejectsNonImages() {
        assertThatThrownBy(() -> service.storeImage(new byte[]{1, 2, 3}, "image/png"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_INVALID_IMAGE);
        assertThatThrownBy(() -> service.storeImage(new byte[]{'G', 'I', 'F', '8'}, "image/gif"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_INVALID_IMAGE);
        verify(imageRepository, never()).save(any());
    }

    @Test
    @DisplayName("상한을 넘는 그림은 413")
    void storeImage_tooLarge() throws IOException {
        ReflectionTestUtils.setField(service, "maxImageBytes", 10L);
        assertThatThrownBy(() -> service.storeImage(png(10, 10), "image/png"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_IMAGE_TOO_LARGE);
    }

    // ── 프레임 ──

    @Test
    @DisplayName("프레임 만들기: 창 4개와 그림을 묶고 en 이 비면 ko 를 복사한다")
    void createFrame_ok() {
        given(imageRepository.findById(image.getId())).willReturn(Optional.of(image));

        CatalogFrameResponse res = service.createFrame(new UpsertFrameRequest(
                new LocalizedText(" 봄 소풍 ", ""), new LocalizedText("들판", null), image.getId(), WINDOWS, 10, null));

        assertThat(res.id()).isEqualTo("af_abcdefghij");
        assertThat(res.title()).isEqualTo(new LocalizedText("봄 소풍", "봄 소풍"));
        assertThat(res.subtitle()).isEqualTo(new LocalizedText("들판", "들판"));
        assertThat(res.windows()).hasSize(4);
        assertThat(res.windows().get(1).rot()).isEqualTo(5);
        assertThat(res.width()).isEqualTo(1062);
        assertThat(res.imageUrl()).endsWith("/catalog/images/" + image.getId());
        assertThat(res.sortOrder()).isEqualTo(10);
        assertThat(res.hidden()).isFalse();
        assertThat(res.updatedAt()).isEqualTo(Instant.parse("2026-10-22T06:00:00Z"));
    }

    @Test
    @DisplayName("프레임 검증: 제목 없음, 창이 4개가 아님, 창이 이미지 밖, 회전 범위 밖, 없는 그림은 모두 400")
    void createFrame_validation() {
        lenient().when(imageRepository.findById(image.getId())).thenReturn(Optional.of(image));
        LocalizedText title = new LocalizedText("제목", null);

        assertInvalidFrame(new UpsertFrameRequest(new LocalizedText("  ", null), null, image.getId(), WINDOWS, null, null));
        assertInvalidFrame(new UpsertFrameRequest(title, null, image.getId(), WINDOWS.subList(0, 3), null, null));
        assertInvalidFrame(new UpsertFrameRequest(title, null, image.getId(),
                List.of(WINDOWS.get(0), WINDOWS.get(1), WINDOWS.get(2), new FrameWindowDto(0.7, 0.43, 0.43, 0.41, 0)), null, null));
        assertInvalidFrame(new UpsertFrameRequest(title, null, image.getId(),
                List.of(WINDOWS.get(0), WINDOWS.get(1), WINDOWS.get(2), new FrameWindowDto(0.51, 0.43, 0.43, 0.41, 200)), null, null));
        assertInvalidFrame(new UpsertFrameRequest(title, null, "nope", WINDOWS, null, null));
        assertInvalidFrame(new UpsertFrameRequest(new LocalizedText("x".repeat(41), null), null, image.getId(), WINDOWS, null, null));
        verify(frameRepository, never()).save(any());
    }

    private void assertInvalidFrame(UpsertFrameRequest request) {
        assertThatThrownBy(() -> service.createFrame(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_INVALID_FRAME);
    }

    @Test
    @DisplayName("목록: 숨김은 빠지고 all 이면 포함, updatedAt 은 숨김까지 통틀어 가장 최근")
    void frames_hiddenFiltering() {
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 20, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 10, 21, 0, 0);
        given(frameRepository.findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc())
                .willReturn(List.of(frame("af_a", false, 0, t1), frame("af_b", true, 1, t2)));

        CatalogFrameListResponse visible = service.frames(false);
        CatalogFrameListResponse all = service.frames(true);

        assertThat(visible.frames()).extracting(CatalogFrameResponse::id).containsExactly("af_a");
        assertThat(all.frames()).extracting(CatalogFrameResponse::id).containsExactly("af_a", "af_b");
        assertThat(visible.updatedAt()).isEqualTo(t2.toInstant(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("빈 목록의 updatedAt 은 null")
    void frames_empty() {
        given(frameRepository.findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc()).willReturn(List.of());

        CatalogFrameListResponse res = service.frames(false);

        assertThat(res.frames()).isEmpty();
        assertThat(res.updatedAt()).isNull();
    }

    @Test
    @DisplayName("숨긴 프레임도 id 로는 열린다 (세션이 참조 중일 수 있다)")
    void frame_hiddenStillReadable() {
        given(frameRepository.findById("af_b")).willReturn(Optional.of(frame("af_b", true, 0, LocalDateTime.now(clock))));

        assertThat(service.frame("af_b").hidden()).isTrue();
        assertThatThrownBy(() -> service.frame("af_zzz"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_FRAME_NOT_FOUND);
    }

    @Test
    @DisplayName("부분 수정: 보낸 필드만 바뀌고, 그림을 바꾸면 이전 그림은 지운다")
    void updateFrame_partialAndImageSwap() {
        CatalogFrame frame = frame("af_a", false, 0, LocalDateTime.of(2026, 10, 1, 0, 0));
        CatalogImage replacement = CatalogImage.builder().id("img000000000000000000C").contentType("image/png").data(new byte[1])
                .sizeBytes(1).width(900).height(1200).createdAt(LocalDateTime.now(clock)).build();
        given(frameRepository.findById("af_a")).willReturn(Optional.of(frame));
        given(imageRepository.findById(replacement.getId())).willReturn(Optional.of(replacement));

        CatalogFrameResponse res = service.updateFrame("af_a", new UpsertFrameRequest(null, null, replacement.getId(), null, null, true));

        assertThat(res.title()).isEqualTo(new LocalizedText("제목", "Title"));   // 안 보낸 제목은 그대로
        assertThat(res.windows()).hasSize(4);                                      // 안 보낸 창도 그대로
        assertThat(res.hidden()).isTrue();
        assertThat(res.width()).isEqualTo(900);
        assertThat(res.imageUrl()).endsWith(replacement.getId());
        assertThat(res.updatedAt()).isEqualTo(Instant.parse("2026-10-22T06:00:00Z"));
        verify(imageRepository).delete(image);
    }

    @Test
    @DisplayName("같은 그림 id 로 수정하면 아무것도 지우지 않는다")
    void updateFrame_sameImageKeepsIt() {
        CatalogFrame frame = frame("af_a", false, 0, LocalDateTime.now(clock));
        given(frameRepository.findById("af_a")).willReturn(Optional.of(frame));

        service.updateFrame("af_a", new UpsertFrameRequest(null, null, image.getId(), null, 3, null));

        verify(imageRepository, never()).delete(any(CatalogImage.class));
        assertThat(frame.getSortOrder()).isEqualTo(3);
    }

    @Test
    @DisplayName("프레임 삭제는 휴지통으로: 행과 그림은 남고 deletedAt·purgeAt(7일 뒤)이 적힌다; id 로는 계속 열리고 수정은 404")
    void deleteFrame_movesToTrash() {
        CatalogFrame frame = frame("af_a", false, 0, LocalDateTime.now(clock));
        given(frameRepository.findById("af_a")).willReturn(Optional.of(frame));

        service.deleteFrame("af_a");

        verify(frameRepository, never()).delete(any(CatalogFrame.class));
        verify(imageRepository, never()).delete(any(CatalogImage.class));
        assertThat(frame.isTrashed()).isTrue();
        assertThat(frame.getPurgeAt()).isEqualTo(LocalDateTime.now(clock).plusDays(7));
        CatalogFrameResponse res = service.frame("af_a");
        assertThat(res.deletedAt()).isEqualTo(Instant.parse("2026-10-22T06:00:00Z"));
        assertThat(res.purgeAt()).isEqualTo(Instant.parse("2026-10-29T06:00:00Z"));
        assertThatThrownBy(() -> service.deleteFrame("af_a"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_FRAME_NOT_FOUND);
        assertThatThrownBy(() -> service.updateFrame("af_a", new UpsertFrameRequest(null, null, null, null, 1, null)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_FRAME_NOT_FOUND);
    }

    @Test
    @DisplayName("휴지통 목록·복구·완전 삭제: 복구는 휴지통에 있을 때만, 완전 삭제는 그림까지")
    void frameTrash_restoreAndPurge() {
        CatalogFrame trashed = frame("af_t", false, 0, LocalDateTime.of(2026, 10, 20, 0, 0));
        trashed.moveToTrash(LocalDateTime.of(2026, 10, 21, 0, 0), LocalDateTime.of(2026, 10, 28, 0, 0));
        CatalogFrame live = frame("af_l", false, 0, LocalDateTime.now(clock));
        given(frameRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtDesc()).willReturn(List.of(trashed));
        given(frameRepository.findById("af_t")).willReturn(Optional.of(trashed));
        given(frameRepository.findById("af_l")).willReturn(Optional.of(live));

        assertThat(service.trashedFrames()).extracting(CatalogFrameResponse::id).containsExactly("af_t");
        assertThatThrownBy(() -> service.restoreFrame("af_l"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_NOT_IN_TRASH);
        assertThatThrownBy(() -> service.purgeFrame("af_l"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_NOT_IN_TRASH);

        CatalogFrameResponse restored = service.restoreFrame("af_t");
        assertThat(restored.deletedAt()).isNull();
        assertThat(restored.purgeAt()).isNull();
        assertThat(restored.updatedAt()).isEqualTo(Instant.parse("2026-10-22T06:00:00Z"));

        trashed.moveToTrash(LocalDateTime.now(clock), LocalDateTime.now(clock).plusDays(7));
        service.purgeFrame("af_t");
        verify(frameRepository).delete(trashed);
        verify(imageRepository).delete(image);
    }

    @Test
    @DisplayName("기한 정리: purgeAt 이 지난 프레임·배너만 그림과 함께 지운다")
    void purgeExpiredTrash() {
        LocalDateTime now = LocalDateTime.now(clock);
        CatalogFrame expired = frame("af_x", false, 0, now.minusDays(9));
        expired.moveToTrash(now.minusDays(8), now.minusDays(1));
        CatalogBanner expiredBanner = CatalogBanner.builder().id("hb_x").titleKo("제목").image(image).width(1).height(1)
                .sortOrder(0).hidden(false).createdAt(now).updatedAt(now).build();
        expiredBanner.moveToTrash(now.minusDays(8), now.minusDays(1));
        given(frameRepository.findAllByPurgeAtBefore(now)).willReturn(List.of(expired));
        given(bannerRepository.findAllByPurgeAtBefore(now)).willReturn(List.of(expiredBanner));

        assertThat(service.purgeExpiredTrash(now)).isEqualTo(2);

        verify(frameRepository).delete(expired);
        verify(bannerRepository).delete(expiredBanner);
        verify(imageRepository, org.mockito.Mockito.times(2)).delete(image);
    }

    // ── 배너 ──

    @Test
    @DisplayName("배너 만들기: action=url 이면 https 주소가 필요하고, 그 외 action 은 linkUrl 을 버린다")
    void createBanner_actions() {
        given(imageRepository.findById(image.getId())).willReturn(Optional.of(image));
        LocalizedText title = new LocalizedText("포즈 고민 없이", "No more pose worries");

        CatalogBannerResponse pose = service.createBanner(new UpsertBannerRequest(title, new LocalizedText("본문", null),
                new LocalizedText("포즈추천", null), image.getId(), "poseGuide", "https://ignored.example", null, null));
        assertThat(pose.action()).isEqualTo("poseGuide");
        assertThat(pose.linkUrl()).isNull();
        assertThat(pose.chip()).isEqualTo(new LocalizedText("포즈추천", "포즈추천"));
        assertThat(pose.title().en()).isEqualTo("No more pose worries");

        CatalogBannerResponse link = service.createBanner(new UpsertBannerRequest(title, null, null, image.getId(), "url",
                " https://peakpic.app/event ", 2, false));
        assertThat(link.linkUrl()).isEqualTo("https://peakpic.app/event");
        assertThat(link.body()).isNull();

        assertThatThrownBy(() -> service.createBanner(new UpsertBannerRequest(title, null, null, image.getId(), "url", "http://x", null, null)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_INVALID_BANNER);
        assertThatThrownBy(() -> service.createBanner(new UpsertBannerRequest(title, null, null, image.getId(), "teleport", null, null, null)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_INVALID_BANNER);
        assertThatThrownBy(() -> service.createBanner(new UpsertBannerRequest(null, null, null, image.getId(), null, null, null, null)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_INVALID_BANNER);
    }

    private CatalogBanner banner(String id, boolean hidden) {
        return CatalogBanner.builder().id(id).titleKo("제목").image(image).width(1).height(1)
                .sortOrder(0).hidden(hidden).createdAt(LocalDateTime.now(clock)).updatedAt(LocalDateTime.now(clock)).build();
    }

    @Test
    @DisplayName("배너 수정·삭제: 다른 배너가 보이고 있으면 숨길 수 있고, 지우면 휴지통으로 간다")
    void updateAndDeleteBanner() {
        CatalogBanner banner = banner("hb_a", false);
        given(bannerRepository.findById("hb_a")).willReturn(Optional.of(banner));
        given(bannerRepository.countByHiddenFalseAndDeletedAtIsNull()).willReturn(2L);

        CatalogBannerResponse res = service.updateBanner("hb_a", new UpsertBannerRequest(null, null, null, null, null, null, null, true));
        assertThat(res.hidden()).isTrue();
        assertThat(res.title()).isEqualTo(new LocalizedText("제목", "제목"));

        service.deleteBanner("hb_a");
        verify(bannerRepository, never()).delete(any(CatalogBanner.class));
        verify(imageRepository, never()).delete(any(CatalogImage.class));
        assertThat(banner.isTrashed()).isTrue();
        assertThat(banner.getPurgeAt()).isEqualTo(LocalDateTime.now(clock).plusDays(7));
    }

    @Test
    @DisplayName("홈 배너는 최소 한 개: 마지막으로 보이는 배너는 숨기거나 지우면 409, 이미 숨긴 배너는 지울 수 있다")
    void lastVisibleBanner_protected() {
        CatalogBanner last = banner("hb_last", false);
        CatalogBanner hidden = banner("hb_hidden", true);
        given(bannerRepository.findById("hb_last")).willReturn(Optional.of(last));
        given(bannerRepository.findById("hb_hidden")).willReturn(Optional.of(hidden));
        given(bannerRepository.countByHiddenFalseAndDeletedAtIsNull()).willReturn(1L);

        assertThatThrownBy(() -> service.updateBanner("hb_last", new UpsertBannerRequest(null, null, null, null, null, null, null, true)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_LAST_BANNER);
        assertThatThrownBy(() -> service.deleteBanner("hb_last"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_LAST_BANNER);
        assertThat(last.isTrashed()).isFalse();
        assertThat(last.isHidden()).isFalse();

        // 제목만 바꾸는 수정은 막지 않는다
        service.updateBanner("hb_last", new UpsertBannerRequest(new LocalizedText("새 제목", null), null, null, null, null, null, null, null));
        assertThat(last.getTitleKo()).isEqualTo("새 제목");

        service.deleteBanner("hb_hidden");
        assertThat(hidden.isTrashed()).isTrue();
    }

    @Test
    @DisplayName("배너 복구·완전 삭제")
    void bannerTrash_restoreAndPurge() {
        CatalogBanner trashed = banner("hb_t", false);
        trashed.moveToTrash(LocalDateTime.now(clock).minusDays(1), LocalDateTime.now(clock).plusDays(6));
        given(bannerRepository.findById("hb_t")).willReturn(Optional.of(trashed));
        given(bannerRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtDesc()).willReturn(List.of(trashed));

        assertThat(service.trashedBanners()).extracting(CatalogBannerResponse::id).containsExactly("hb_t");
        assertThat(service.restoreBanner("hb_t").deletedAt()).isNull();
        assertThat(trashed.isTrashed()).isFalse();
        assertThatThrownBy(() -> service.purgeBanner("hb_t"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_NOT_IN_TRASH);

        trashed.moveToTrash(LocalDateTime.now(clock), LocalDateTime.now(clock).plusDays(7));
        service.purgeBanner("hb_t");
        verify(bannerRepository).delete(trashed);
        verify(imageRepository).delete(image);
    }

    // ── 정리 ──

    @Test
    @DisplayName("어디서도 안 쓰는 그림만 지운다")
    void purgeOrphanImages() {
        LocalDateTime before = LocalDateTime.now(clock).minusDays(1);
        given(imageRepository.findOrphanIds(before)).willReturn(List.of("x", "y"));

        int purged = service.purgeOrphanImages(before);

        assertThat(purged).isEqualTo(2);
        verify(imageRepository).deleteById("x");
        verify(imageRepository).deleteById("y");
    }

    @Test
    @DisplayName("정리 스케줄러는 기한 지난 휴지통을 비운 뒤 하루 지난 고아 그림을 지운다")
    void cleanupScheduler() {
        LocalDateTime now = LocalDateTime.now(clock);
        given(imageRepository.findOrphanIds(now.minusDays(1))).willReturn(List.of("x"));
        CatalogPoseService poseService = org.mockito.Mockito.mock(CatalogPoseService.class);
        given(poseService.purgeExpiredTrash(now)).willReturn(3);

        assertThat(new CatalogCleanupScheduler(service, poseService, clock).runOnce(now)).isEqualTo(4);
        verify(poseService).purgeExpiredTrash(now);
    }
}
