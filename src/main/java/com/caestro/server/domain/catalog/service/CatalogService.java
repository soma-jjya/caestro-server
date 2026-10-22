package com.caestro.server.domain.catalog.service;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.dto.LocalizedText;
import com.caestro.server.domain.catalog.dto.request.UpsertBannerRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertFrameRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogBannerListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogBannerResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogImageResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogVideoResponse;
import com.caestro.server.domain.catalog.entity.CatalogBanner;
import com.caestro.server.domain.catalog.entity.CatalogFrame;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.repository.CatalogBannerRepository;
import com.caestro.server.domain.catalog.repository.CatalogFrameRepository;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카탈로그(디자이너 스튜디오): 서버에서 내려주는 일러스트 프레임과 홈 배너 (contracts/catalog-studio.md).
 * 읽기는 누구나(숨김은 목록에서 빠짐), 쓰기는 스튜디오 키. 그림은 따로 올린 뒤 id 로 묶고,
 * 그림을 바꾸면 쓰이지 않게 된 그림은 함께 지운다. 지우기는 휴지통({@code deletedAt}/{@code purgeAt})으로 가고
 * 기한(catalog.trash-days)이 지나면 정리 배치가 그림과 함께 완전히 지운다. 홈 배너는 최소 한 개는 보여야 한다.
 */
@Service
public class CatalogService {

    static final int WINDOWS = 4;
    static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg");
    static final String VIDEO_TYPE = "video/mp4";
    static final int VIDEO_MAX_SIDE = 4096;
    static final int VIDEO_MIN_MS = 500;
    static final int VIDEO_MAX_MS = 60_000;
    static final Set<String> BANNER_ACTIONS = Set.of("poseGuide", "fourCut", "fillTogether", "frameLibrary", "url");
    private static final TypeReference<List<FrameWindowDto>> WINDOW_LIST = new TypeReference<>() {
    };

    private final CatalogFrameRepository frameRepository;
    private final CatalogBannerRepository bannerRepository;
    private final CatalogImageRepository imageRepository;
    private final CatalogIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${catalog.max-image-bytes:8388608}")
    private long maxImageBytes;

    @Value("${catalog.max-video-bytes:15728640}")
    private long maxVideoBytes;

    @Value("${catalog.trash-days:7}")
    private int trashDays;

    // 그림 주소의 호스트. fill 과 같이 요청에서 만들지 않고 고정한다(ALB 뒤에서는 요청 스킴을 믿을 수 없다)
    @Value("${catalog.public-base-url:https://api.peakpic.app}")
    private String publicBaseUrl;

    public CatalogService(CatalogFrameRepository frameRepository, CatalogBannerRepository bannerRepository,
                          CatalogImageRepository imageRepository, CatalogIdGenerator idGenerator,
                          ObjectMapper objectMapper, Clock clock) {
        this.frameRepository = frameRepository;
        this.bannerRepository = bannerRepository;
        this.imageRepository = imageRepository;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ── 그림 ─────────────────────────────────────────────────────────────────

    /** PNG/JPEG 바이트를 저장하고 폭·높이를 읽어 돌려준다. 읽히지 않는 파일은 400. */
    @Transactional
    public CatalogImageResponse storeImage(byte[] body, String contentType) {
        String type = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase();
        if (body == null || body.length == 0 || !IMAGE_TYPES.contains(type)) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_IMAGE);
        }
        if (body.length > maxImageBytes) {
            throw new CustomException(ErrorCode.CATALOG_IMAGE_TOO_LARGE);
        }
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(body));
        } catch (IOException e) {
            decoded = null;
        }
        if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_IMAGE);
        }
        CatalogImage image = imageRepository.save(CatalogImage.builder()
                .id(idGenerator.imageId())
                .contentType(type)
                .data(body)
                .sizeBytes(body.length)
                .width(decoded.getWidth())
                .height(decoded.getHeight())
                .createdAt(LocalDateTime.now(clock))
                .build());
        return new CatalogImageResponse(image.getId(), imageUrl(image.getId()), image.getWidth(), image.getHeight(), image.getContentType());
    }

    /**
     * 배너 동영상(MP4) 바이트를 저장한다. 폭·높이·길이는 브라우저가 읽어 보낸 값을 범위만 보고 믿는다(서버는 디코딩하지 않는다).
     * MP4 가 아니면(앞 8바이트의 ftyp 상자) 400, 상한 초과 413.
     */
    @Transactional
    public CatalogVideoResponse storeVideo(byte[] body, String contentType, Integer width, Integer height, Integer durationMs) {
        String type = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase();
        if (body == null || body.length < 12 || !VIDEO_TYPE.equals(type)) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_VIDEO);
        }
        if (body.length > maxVideoBytes) {
            throw new CustomException(ErrorCode.CATALOG_VIDEO_TOO_LARGE);
        }
        if (!(body[4] == 'f' && body[5] == 't' && body[6] == 'y' && body[7] == 'p')) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_VIDEO);
        }
        if (width == null || height == null || durationMs == null
                || width < 1 || width > VIDEO_MAX_SIDE || height < 1 || height > VIDEO_MAX_SIDE
                || durationMs < VIDEO_MIN_MS || durationMs > VIDEO_MAX_MS) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_VIDEO);
        }
        CatalogImage video = imageRepository.save(CatalogImage.builder()
                .id(idGenerator.imageId())
                .contentType(VIDEO_TYPE)
                .data(body)
                .sizeBytes(body.length)
                .width(width)
                .height(height)
                .durationMs(durationMs)
                .createdAt(LocalDateTime.now(clock))
                .build());
        return new CatalogVideoResponse(video.getId(), imageUrl(video.getId()), video.getWidth(), video.getHeight(),
                video.getDurationMs(), video.getContentType());
    }

    @Transactional(readOnly = true)
    public StoredImage image(String imageId) {
        CatalogImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new CustomException(ErrorCode.CATALOG_IMAGE_NOT_FOUND));
        return new StoredImage(image.getId(), image.getContentType(), image.getData());
    }

    /** 어떤 프레임·배너도 안 쓰는 그림 중 {@code before} 이전에 올라온 것을 지운다. 지운 수를 돌려준다. */
    @Transactional
    public int purgeOrphanImages(LocalDateTime before) {
        List<String> ids = imageRepository.findOrphanIds(before);
        for (String id : ids) {
            imageRepository.deleteById(id);
        }
        return ids.size();
    }

    // ── 프레임 ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CatalogFrameListResponse frames(boolean includeHidden) {
        List<CatalogFrame> all = frameRepository.findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc();
        List<CatalogFrameResponse> rows = all.stream()
                .filter(f -> includeHidden || !f.isHidden())
                .map(this::toResponse)
                .toList();
        return new CatalogFrameListResponse(latest(all.stream().map(CatalogFrame::getUpdatedAt).toList()), rows);
    }

    @Transactional(readOnly = true)
    public CatalogFrameResponse frame(String id) {
        return toResponse(frameOf(id));
    }

    @Transactional
    public CatalogFrameResponse createFrame(UpsertFrameRequest request) {
        LocalizedText title = requireText(request.title(), 40, true, ErrorCode.CATALOG_INVALID_FRAME);
        LocalizedText subtitle = requireText(request.subtitle(), 80, false, ErrorCode.CATALOG_INVALID_FRAME);
        List<FrameWindowDto> windows = validWindows(request.windows());
        if (windows == null) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_FRAME);
        }
        CatalogImage image = imageOf(request.imageId(), ErrorCode.CATALOG_INVALID_FRAME);
        LocalizedText badge = requireText(request.badge(), BADGE_MAX, false, ErrorCode.CATALOG_INVALID_FRAME);
        LocalDateTime now = LocalDateTime.now(clock);
        CatalogFrame frame = frameRepository.save(CatalogFrame.builder()
                .id(idGenerator.frameId())
                .titleKo(title.ko()).titleEn(title.en())
                .subtitleKo(subtitle == null ? null : subtitle.ko()).subtitleEn(subtitle == null ? null : subtitle.en())
                .image(image).width(image.getWidth()).height(image.getHeight())
                .windowsJson(toJson(windows))
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                .hidden(Boolean.TRUE.equals(request.hidden()))
                // 새 프레임은 Pro 가 기본(스튜디오 토글 기본값과 같다)
                .pro(!Boolean.FALSE.equals(request.pro()))
                .badgeKo(badge == null ? null : badge.ko()).badgeEn(badge == null ? null : badge.en())
                .createdAt(now).updatedAt(now)
                .build());
        return toResponse(frame);
    }

    /** 보낸 필드만 바꾼다. 그림을 바꾸면 이전 그림은 지운다. */
    @Transactional
    public CatalogFrameResponse updateFrame(String id, UpsertFrameRequest request) {
        CatalogFrame frame = liveFrameOf(id);
        LocalizedText title = request.title() == null ? LocalizedText.of(frame.getTitleKo(), frame.getTitleEn())
                : requireText(request.title(), 40, true, ErrorCode.CATALOG_INVALID_FRAME);
        LocalizedText subtitle = request.subtitle() == null ? LocalizedText.of(frame.getSubtitleKo(), frame.getSubtitleEn())
                : requireText(request.subtitle(), 80, false, ErrorCode.CATALOG_INVALID_FRAME);
        frame.rename(title.ko(), title.en(), subtitle == null ? null : subtitle.ko(), subtitle == null ? null : subtitle.en());
        if (request.windows() != null) {
            List<FrameWindowDto> windows = validWindows(request.windows());
            if (windows == null) {
                throw new CustomException(ErrorCode.CATALOG_INVALID_FRAME);
            }
            frame.setWindowsJson(toJson(windows));
        }
        if (request.imageId() != null && !request.imageId().equals(frame.getImage().getId())) {
            CatalogImage previous = frame.getImage();
            frame.replaceImage(imageOf(request.imageId(), ErrorCode.CATALOG_INVALID_FRAME));
            imageRepository.delete(previous);
        }
        if (request.sortOrder() != null) {
            frame.setSortOrder(request.sortOrder());
        }
        if (request.hidden() != null) {
            frame.setHidden(request.hidden());
        }
        if (request.pro() != null) {
            frame.setPro(request.pro());
        }
        if (request.badge() != null) {
            // 한국어를 비워 보내면 배지를 지운다
            LocalizedText badge = requireText(request.badge(), BADGE_MAX, false, ErrorCode.CATALOG_INVALID_FRAME);
            frame.setBadge(badge == null ? null : badge.ko(), badge == null ? null : badge.en());
        }
        frame.touch(LocalDateTime.now(clock));
        return toResponse(frame);
    }

    /** 휴지통으로: 목록에서 빠지되 id 로는 계속 열린다. 이미 휴지통이면 404. */
    @Transactional
    public void deleteFrame(String id) {
        CatalogFrame frame = frameOf(id);
        if (frame.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_FRAME_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        frame.moveToTrash(now, now.plusDays(trashDays));
    }

    @Transactional
    public CatalogFrameResponse restoreFrame(String id) {
        CatalogFrame frame = frameOf(id);
        if (!frame.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_NOT_IN_TRASH);
        }
        frame.restore(LocalDateTime.now(clock));
        return toResponse(frame);
    }

    /** 휴지통에서 완전히: 그림도 지운다. */
    @Transactional
    public void purgeFrame(String id) {
        CatalogFrame frame = frameOf(id);
        if (!frame.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_NOT_IN_TRASH);
        }
        eraseFrame(frame);
    }

    private void eraseFrame(CatalogFrame frame) {
        CatalogImage image = frame.getImage();
        frameRepository.delete(frame);
        imageRepository.delete(image);
    }

    @Transactional(readOnly = true)
    public List<CatalogFrameResponse> trashedFrames() {
        return frameRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtDesc().stream().map(this::toResponse).toList();
    }

    // ── 배너 ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CatalogBannerListResponse banners(boolean includeHidden) {
        List<CatalogBanner> all = bannerRepository.findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc();
        List<CatalogBannerResponse> rows = all.stream()
                .filter(b -> includeHidden || !b.isHidden())
                .map(this::toResponse)
                .toList();
        return new CatalogBannerListResponse(latest(all.stream().map(CatalogBanner::getUpdatedAt).toList()), rows);
    }

    @Transactional
    public CatalogBannerResponse createBanner(UpsertBannerRequest request) {
        LocalizedText title = requireText(request.title(), 60, true, ErrorCode.CATALOG_INVALID_BANNER);
        LocalizedText body = requireText(request.body(), 120, false, ErrorCode.CATALOG_INVALID_BANNER);
        LocalizedText chip = requireText(request.chip(), 20, false, ErrorCode.CATALOG_INVALID_BANNER);
        String action = validAction(request.action(), request.linkUrl());
        CatalogImage image = imageOf(request.imageId(), ErrorCode.CATALOG_INVALID_BANNER);
        CatalogImage video = request.videoId() == null || request.videoId().isBlank() ? null : videoOf(request.videoId());
        LocalDateTime now = LocalDateTime.now(clock);
        CatalogBanner banner = bannerRepository.save(CatalogBanner.builder()
                .id(idGenerator.bannerId())
                .titleKo(title.ko()).titleEn(title.en())
                .bodyKo(body == null ? null : body.ko()).bodyEn(body == null ? null : body.en())
                .chipKo(chip == null ? null : chip.ko()).chipEn(chip == null ? null : chip.en())
                .image(image).width(image.getWidth()).height(image.getHeight())
                .video(video)
                .action(action).linkUrl("url".equals(action) ? request.linkUrl().trim() : null)
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                .hidden(Boolean.TRUE.equals(request.hidden()))
                .createdAt(now).updatedAt(now)
                .build());
        return toResponse(banner);
    }

    @Transactional
    public CatalogBannerResponse updateBanner(String id, UpsertBannerRequest request) {
        CatalogBanner banner = liveBannerOf(id);
        // 홍보 자료는 최소 한 개: 마지막으로 보이는 배너는 숨길 수 없다
        if (Boolean.TRUE.equals(request.hidden()) && !banner.isHidden()) {
            requireAnotherVisibleBanner();
        }
        if (request.title() != null) {
            LocalizedText t = requireText(request.title(), 60, true, ErrorCode.CATALOG_INVALID_BANNER);
            banner.setTitle(t.ko(), t.en());
        }
        if (request.body() != null) {
            LocalizedText t = requireText(request.body(), 120, false, ErrorCode.CATALOG_INVALID_BANNER);
            banner.setBody(t == null ? null : t.ko(), t == null ? null : t.en());
        }
        if (request.chip() != null) {
            LocalizedText t = requireText(request.chip(), 20, false, ErrorCode.CATALOG_INVALID_BANNER);
            banner.setChip(t == null ? null : t.ko(), t == null ? null : t.en());
        }
        if (request.action() != null || request.linkUrl() != null) {
            String action = request.action() != null ? request.action() : banner.getAction();
            String link = request.linkUrl() != null ? request.linkUrl() : banner.getLinkUrl();
            String valid = validAction(action, link);
            banner.setAction(valid, "url".equals(valid) ? link.trim() : null);
        }
        if (request.imageId() != null && !request.imageId().equals(banner.getImage().getId())) {
            CatalogImage previous = banner.getImage();
            banner.replaceImage(imageOf(request.imageId(), ErrorCode.CATALOG_INVALID_BANNER));
            imageRepository.delete(previous);
        }
        // 동영상: clearVideo 가 먼저(떼기), 아니면 다른 videoId 로 바꾸기. 쓰이지 않게 된 동영상은 지운다
        if (Boolean.TRUE.equals(request.clearVideo())) {
            CatalogImage previous = banner.replaceVideo(null);
            if (previous != null) {
                imageRepository.delete(previous);
            }
        } else if (request.videoId() != null && !request.videoId().isBlank()
                && (banner.getVideo() == null || !request.videoId().equals(banner.getVideo().getId()))) {
            CatalogImage previous = banner.replaceVideo(videoOf(request.videoId()));
            if (previous != null) {
                imageRepository.delete(previous);
            }
        }
        if (request.sortOrder() != null) {
            banner.setSortOrder(request.sortOrder());
        }
        if (request.hidden() != null) {
            banner.setHidden(request.hidden());
        }
        banner.touch(LocalDateTime.now(clock));
        return toResponse(banner);
    }

    /** 휴지통으로. 마지막으로 보이는 배너는 지울 수 없다(409). */
    @Transactional
    public void deleteBanner(String id) {
        CatalogBanner banner = liveBannerOf(id);
        if (!banner.isHidden()) {
            requireAnotherVisibleBanner();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        banner.moveToTrash(now, now.plusDays(trashDays));
    }

    @Transactional
    public CatalogBannerResponse restoreBanner(String id) {
        CatalogBanner banner = bannerOf(id);
        if (!banner.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_NOT_IN_TRASH);
        }
        banner.restore(LocalDateTime.now(clock));
        return toResponse(banner);
    }

    @Transactional
    public void purgeBanner(String id) {
        CatalogBanner banner = bannerOf(id);
        if (!banner.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_NOT_IN_TRASH);
        }
        eraseBanner(banner);
    }

    private void eraseBanner(CatalogBanner banner) {
        CatalogImage image = banner.getImage();
        CatalogImage video = banner.getVideo();
        bannerRepository.delete(banner);
        imageRepository.delete(image);
        if (video != null) {
            imageRepository.delete(video);
        }
    }

    @Transactional(readOnly = true)
    public List<CatalogBannerResponse> trashedBanners() {
        return bannerRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtDesc().stream().map(this::toResponse).toList();
    }

    private void requireAnotherVisibleBanner() {
        if (bannerRepository.countByHiddenFalseAndDeletedAtIsNull() <= 1) {
            throw new CustomException(ErrorCode.CATALOG_LAST_BANNER);
        }
    }

    // ── 휴지통 정리 ───────────────────────────────────────────────────────────

    /** 기한(purgeAt)이 지난 프레임·배너를 그림과 함께 완전히 지운다. 지운 수를 돌려준다. */
    @Transactional
    public int purgeExpiredTrash(LocalDateTime now) {
        List<CatalogFrame> frames = frameRepository.findAllByPurgeAtBefore(now);
        frames.forEach(this::eraseFrame);
        List<CatalogBanner> banners = bannerRepository.findAllByPurgeAtBefore(now);
        banners.forEach(this::eraseBanner);
        return frames.size() + banners.size();
    }

    // ── 검증·변환 ─────────────────────────────────────────────────────────────

    /**
     * ko 는 다듬어 길이를 보고, en 은 비면 ko 로 채운다(응답 규칙). {@code required} 가 아니고 ko 가 비면 null —
     * 그 문구가 없는 것으로 저장한다.
     */
    /** 배지 한국어 글자 수 상한(영어는 1.5배). 스튜디오 입력란도 이 안이다 */
    static final int BADGE_MAX = 8;

    static LocalizedText requireText(LocalizedText text, int maxKo, boolean required, ErrorCode error) {
        String ko = text == null || text.ko() == null ? "" : text.ko().strip();
        if (ko.isEmpty()) {
            if (required) {
                throw new CustomException(error);
            }
            return null;
        }
        if (ko.length() > maxKo) {
            throw new CustomException(error);
        }
        String en = text.en() == null ? "" : text.en().strip();
        if (en.length() > maxKo * 3 / 2) {
            throw new CustomException(error);
        }
        return LocalizedText.of(ko, en);
    }

    /** 창 4개가 모두 이미지 안에 있고 회전이 -180..180 이면 다듬은 목록, 아니면 null. */
    static List<FrameWindowDto> validWindows(List<FrameWindowDto> windows) {
        if (windows == null || windows.size() != WINDOWS) {
            return null;
        }
        for (FrameWindowDto w : windows) {
            if (w == null || !finite(w.x()) || !finite(w.y()) || !finite(w.w()) || !finite(w.h()) || !finite(w.rot())) {
                return null;
            }
            if (w.x() < 0 || w.y() < 0 || w.w() <= 0 || w.h() <= 0 || w.x() + w.w() > 1.001 || w.y() + w.h() > 1.001
                    || w.rot() < -180 || w.rot() > 180) {
                return null;
            }
        }
        return windows.stream().map(w -> new FrameWindowDto(w.x(), w.y(), w.w(), w.h(), w.rot())).toList();
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** action 은 정해진 값 중 하나(null 은 동작 없음). url 이면 https 주소가 있어야 한다. */
    static String validAction(String action, String linkUrl) {
        if (action == null || action.isBlank()) {
            return null;
        }
        if (!BANNER_ACTIONS.contains(action)) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_BANNER);
        }
        if ("url".equals(action)) {
            String link = linkUrl == null ? "" : linkUrl.strip();
            if (!link.startsWith("https://") || link.length() > 500) {
                throw new CustomException(ErrorCode.CATALOG_INVALID_BANNER);
            }
        }
        return action;
    }

    private CatalogImage imageOf(String imageId, ErrorCode error) {
        if (imageId == null || imageId.isBlank()) {
            throw new CustomException(error);
        }
        return imageRepository.findById(imageId).orElseThrow(() -> new CustomException(error));
    }

    /** 배너에 붙일 동영상: /catalog/studio/videos 로 올린 video/mp4 행이어야 한다. */
    private CatalogImage videoOf(String videoId) {
        CatalogImage video = imageRepository.findById(videoId)
                .orElseThrow(() -> new CustomException(ErrorCode.CATALOG_INVALID_BANNER));
        if (!VIDEO_TYPE.equals(video.getContentType())) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_BANNER);
        }
        return video;
    }

    /** 휴지통에 있어도 돌려준다(세션이 참조 중일 수 있다). */
    private CatalogFrame frameOf(String id) {
        return frameRepository.findById(id).orElseThrow(() -> new CustomException(ErrorCode.CATALOG_FRAME_NOT_FOUND));
    }

    /** 수정·삭제용: 휴지통에 있으면 없는 것으로 본다. */
    private CatalogFrame liveFrameOf(String id) {
        CatalogFrame frame = frameOf(id);
        if (frame.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_FRAME_NOT_FOUND);
        }
        return frame;
    }

    private CatalogBanner bannerOf(String id) {
        return bannerRepository.findById(id).orElseThrow(() -> new CustomException(ErrorCode.CATALOG_BANNER_NOT_FOUND));
    }

    private CatalogBanner liveBannerOf(String id) {
        CatalogBanner banner = bannerOf(id);
        if (banner.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_BANNER_NOT_FOUND);
        }
        return banner;
    }

    private CatalogFrameResponse toResponse(CatalogFrame f) {
        return new CatalogFrameResponse(f.getId(), LocalizedText.of(f.getTitleKo(), f.getTitleEn()),
                LocalizedText.of(f.getSubtitleKo(), f.getSubtitleEn()), imageUrl(f.getImage().getId()),
                f.getWidth(), f.getHeight(), fromJson(f.getWindowsJson()), f.getSortOrder(), f.isHidden(),
                toInstant(f.getCreatedAt()), toInstant(f.getUpdatedAt()), toInstant(f.getDeletedAt()), toInstant(f.getPurgeAt()),
                f.isPro(), LocalizedText.of(f.getBadgeKo(), f.getBadgeEn()));
    }

    private CatalogBannerResponse toResponse(CatalogBanner b) {
        return new CatalogBannerResponse(b.getId(), LocalizedText.of(b.getTitleKo(), b.getTitleEn()),
                LocalizedText.of(b.getBodyKo(), b.getBodyEn()), LocalizedText.of(b.getChipKo(), b.getChipEn()),
                imageUrl(b.getImage().getId()), b.getWidth(), b.getHeight(), b.getAction(), b.getLinkUrl(),
                b.getSortOrder(), b.isHidden(), toInstant(b.getCreatedAt()), toInstant(b.getUpdatedAt()),
                toInstant(b.getDeletedAt()), toInstant(b.getPurgeAt()),
                b.getVideo() == null ? null : imageUrl(b.getVideo().getId()),
                b.getVideo() == null ? null : b.getVideo().getDurationMs());
    }

    public String imageUrl(String imageId) {
        return publicBaseUrl + "/catalog/images/" + imageId;
    }

    private Instant latest(List<LocalDateTime> times) {
        return times.stream().filter(Objects::nonNull).max(Comparator.naturalOrder()).map(this::toInstant).orElse(null);
    }

    Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.atZone(clock.getZone()).toInstant();
    }

    private String toJson(List<FrameWindowDto> windows) {
        try {
            return objectMapper.writeValueAsString(windows);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_FRAME);
        }
    }

    private List<FrameWindowDto> fromJson(String json) {
        try {
            return objectMapper.readValue(json, WINDOW_LIST);
        } catch (IOException e) {
            return List.of();
        }
    }
}
