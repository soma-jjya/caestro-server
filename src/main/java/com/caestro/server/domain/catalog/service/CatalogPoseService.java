package com.caestro.server.domain.catalog.service;

import com.caestro.server.domain.catalog.dto.request.UpsertPoseOverrideRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertPoseUploadRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseOverrideResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseUploadResponse;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.entity.CatalogPoseOverride;
import com.caestro.server.domain.catalog.entity.CatalogPoseUpload;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.domain.catalog.repository.CatalogPoseOverrideRepository;
import com.caestro.server.domain.catalog.repository.CatalogPoseUploadRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포즈추천 카탈로그 (contracts/catalog-studio.md 2차). 파이프라인이 S3 에 올린 포즈 풀은 건드리지 않고
 * 두 가지를 덧씌운다: 기존 샷의 숨김·라벨(overrides), 디자이너가 올린 AI 사진(uploads).
 * 라벨 키는 앱 meta 와 같은 한국어 토큰(장소·성별·인종·계절)이며 빈 값은 키를 뺀다. 인종은 앱에 보이지 않는 내부 분류다.
 */
@Service
public class CatalogPoseService {

    static final String LABEL_PLACE = "장소";
    static final String LABEL_GENDER = "성별";
    static final String LABEL_RACE = "인종";
    static final String LABEL_SEASON = "계절";
    static final List<String> LABEL_KEYS = List.of(LABEL_PLACE, LABEL_GENDER, LABEL_RACE, LABEL_SEASON);
    static final Set<String> GENDERS = Set.of("남자", "여자");
    static final Set<String> SEASONS = Set.of("봄·가을", "여름", "겨울");
    // 인종은 스튜디오에서 고르는 두 값만 (앱에는 보이지 않는 내부 분류)
    static final Set<String> RACES = Set.of("동양", "서양");
    static final int LABEL_MAX = 20;
    private static final TypeReference<Map<String, String>> LABEL_MAP = new TypeReference<>() {
    };

    private final CatalogPoseOverrideRepository overrideRepository;
    private final CatalogPoseUploadRepository uploadRepository;
    private final CatalogImageRepository imageRepository;
    private final CatalogIdGenerator idGenerator;
    private final CatalogService catalogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${catalog.trash-days:7}")
    private int trashDays;

    public CatalogPoseService(CatalogPoseOverrideRepository overrideRepository, CatalogPoseUploadRepository uploadRepository,
                              CatalogImageRepository imageRepository, CatalogIdGenerator idGenerator,
                              CatalogService catalogService, ObjectMapper objectMapper, Clock clock) {
        this.overrideRepository = overrideRepository;
        this.uploadRepository = uploadRepository;
        this.imageRepository = imageRepository;
        this.idGenerator = idGenerator;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ── 읽기 ─────────────────────────────────────────────────────────────────

    /**
     * 덧씌우기는 숨김이거나 라벨이 있는 것만(휴지통은 hidden=true 로), 업로드는 살아 있는 것만.
     * {@code includeHidden} 이 아니면 숨긴 업로드도 뺀다. updatedAt 은 둘을 통틀어 가장 최근.
     */
    @Transactional(readOnly = true)
    public CatalogPoseResponse pose(boolean includeHidden) {
        List<CatalogPoseOverride> overrides = overrideRepository.findAllByOrderByVersionAscShotAsc();
        List<CatalogPoseUpload> uploads = uploadRepository.findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc();
        List<CatalogPoseOverrideResponse> overrideRows = overrides.stream()
                .filter(o -> o.isHidden() || o.getLabelsJson() != null || o.isTrashed())
                .map(this::toResponse)
                .toList();
        List<CatalogPoseUploadResponse> uploadRows = uploads.stream()
                .filter(u -> includeHidden || !u.isHidden())
                .map(this::toResponse)
                .toList();
        List<LocalDateTime> stamps = new ArrayList<>();
        overrides.forEach(o -> stamps.add(o.getUpdatedAt()));
        uploads.forEach(u -> stamps.add(u.getUpdatedAt()));
        Instant latest = stamps.stream().filter(Objects::nonNull).max(Comparator.naturalOrder())
                .map(catalogService::toInstant).orElse(null);
        return new CatalogPoseResponse(latest, overrideRows, uploadRows);
    }

    // ── 덧씌우기 ──────────────────────────────────────────────────────────────

    /**
     * 보낸 필드만 바꾼다. deleted=true 는 휴지통(숨김 + 기한), deleted=false 는 휴지통에서 꺼냄.
     * 끝나고 덧씌울 것이 하나도 없으면 행을 지운다.
     */
    @Transactional
    public CatalogPoseOverrideResponse upsertOverride(String version, String shot, UpsertPoseOverrideRequest request) {
        if (version == null || version.isBlank() || version.length() > 20 || shot == null || shot.isBlank() || shot.length() > 80) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_POSE);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        CatalogPoseOverride override = overrideRepository.findByVersionAndShot(version, shot)
                .orElseGet(() -> CatalogPoseOverride.builder().version(version).shot(shot).hidden(false)
                        .createdAt(now).updatedAt(now).build());
        if (request.labels() != null) {
            Map<String, String> labels = cleanLabels(request.labels(), false);
            override.setLabelsJson(labels.isEmpty() ? null : toJson(labels));
        }
        if (request.hidden() != null) {
            override.setHidden(request.hidden());
        }
        if (Boolean.TRUE.equals(request.deleted())) {
            override.moveToTrash(now, now.plusDays(trashDays));
        } else if (Boolean.FALSE.equals(request.deleted()) && override.isTrashed()) {
            override.restore(now);
        }
        override.touch(now);
        if (override.isEmpty()) {
            if (override.getId() != null) {
                overrideRepository.delete(override);
            }
            return toResponse(override);
        }
        return toResponse(overrideRepository.save(override));
    }

    @Transactional
    public CatalogPoseOverrideResponse restoreOverride(String version, String shot) {
        CatalogPoseOverride override = overrideRepository.findByVersionAndShot(version, shot)
                .orElseThrow(() -> new CustomException(ErrorCode.CATALOG_POSE_OVERRIDE_NOT_FOUND));
        if (!override.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_NOT_IN_TRASH);
        }
        override.restore(LocalDateTime.now(clock));
        if (override.isEmpty()) {
            overrideRepository.delete(override);
        }
        return toResponse(override);
    }

    @Transactional(readOnly = true)
    public List<CatalogPoseOverrideResponse> trashedOverrides() {
        return overrideRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtDesc().stream().map(this::toResponse).toList();
    }

    // ── 업로드 ────────────────────────────────────────────────────────────────

    @Transactional
    public CatalogPoseUploadResponse createUpload(UpsertPoseUploadRequest request) {
        Map<String, String> labels = cleanLabels(request.labels(), true);
        CatalogImage image = imageOf(request.imageId());
        LocalDateTime now = LocalDateTime.now(clock);
        CatalogPoseUpload upload = uploadRepository.save(CatalogPoseUpload.builder()
                .id(idGenerator.poseUploadId())
                .image(image).width(image.getWidth()).height(image.getHeight())
                .labelsJson(toJson(labels))
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                .hidden(Boolean.TRUE.equals(request.hidden()))
                .createdAt(now).updatedAt(now)
                .build());
        return toResponse(upload);
    }

    /** 보낸 필드만 바꾼다(labels 는 통째로 교체). 그림을 바꾸면 이전 그림은 지운다. */
    @Transactional
    public CatalogPoseUploadResponse updateUpload(String id, UpsertPoseUploadRequest request) {
        CatalogPoseUpload upload = liveUploadOf(id);
        if (request.labels() != null) {
            upload.setLabelsJson(toJson(cleanLabels(request.labels(), true)));
        }
        if (request.imageId() != null && !request.imageId().equals(upload.getImage().getId())) {
            CatalogImage previous = upload.getImage();
            upload.replaceImage(imageOf(request.imageId()));
            imageRepository.delete(previous);
        }
        if (request.sortOrder() != null) {
            upload.setSortOrder(request.sortOrder());
        }
        if (request.hidden() != null) {
            upload.setHidden(request.hidden());
        }
        upload.touch(LocalDateTime.now(clock));
        return toResponse(upload);
    }

    @Transactional
    public void deleteUpload(String id) {
        CatalogPoseUpload upload = liveUploadOf(id);
        LocalDateTime now = LocalDateTime.now(clock);
        upload.moveToTrash(now, now.plusDays(trashDays));
    }

    @Transactional
    public CatalogPoseUploadResponse restoreUpload(String id) {
        CatalogPoseUpload upload = uploadOf(id);
        if (!upload.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_NOT_IN_TRASH);
        }
        upload.restore(LocalDateTime.now(clock));
        return toResponse(upload);
    }

    @Transactional
    public void purgeUpload(String id) {
        CatalogPoseUpload upload = uploadOf(id);
        if (!upload.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_NOT_IN_TRASH);
        }
        eraseUpload(upload);
    }

    @Transactional(readOnly = true)
    public List<CatalogPoseUploadResponse> trashedUploads() {
        return uploadRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtDesc().stream().map(this::toResponse).toList();
    }

    // ── 휴지통 정리 ───────────────────────────────────────────────────────────

    /** 기한이 지난 업로드는 그림과 함께 지우고, 덧씌우기는 숨김으로 영구히 남긴다. 처리한 수를 돌려준다. */
    @Transactional
    public int purgeExpiredTrash(LocalDateTime now) {
        List<CatalogPoseUpload> uploads = uploadRepository.findAllByPurgeAtBefore(now);
        uploads.forEach(this::eraseUpload);
        List<CatalogPoseOverride> overrides = overrideRepository.findAllByPurgeAtBefore(now);
        overrides.forEach(o -> o.purgeKeepHidden(now));
        return uploads.size() + overrides.size();
    }

    private void eraseUpload(CatalogPoseUpload upload) {
        CatalogImage image = upload.getImage();
        uploadRepository.delete(upload);
        imageRepository.delete(image);
    }

    // ── 검증·변환 ─────────────────────────────────────────────────────────────

    /**
     * 네 가지 키만 남기고 값을 다듬는다. 빈 값은 키를 뺀다. 장소는 {@code requirePlace} 일 때 필수,
     * 성별·계절·인종은 허용값만, 모두 20자 이하. 어긋나면 400.
     */
    static Map<String, String> cleanLabels(Map<String, String> raw, boolean requirePlace) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw != null) {
            for (String key : LABEL_KEYS) {
                String value = raw.get(key);
                String cleaned = value == null ? "" : value.strip();
                if (cleaned.isEmpty()) {
                    continue;
                }
                if (cleaned.length() > LABEL_MAX) {
                    throw new CustomException(ErrorCode.CATALOG_INVALID_POSE);
                }
                out.put(key, cleaned);
            }
        }
        if (requirePlace && !out.containsKey(LABEL_PLACE)) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_POSE);
        }
        if (out.containsKey(LABEL_GENDER) && !GENDERS.contains(out.get(LABEL_GENDER))) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_POSE);
        }
        if (out.containsKey(LABEL_SEASON) && !SEASONS.contains(out.get(LABEL_SEASON))) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_POSE);
        }
        if (out.containsKey(LABEL_RACE) && !RACES.contains(out.get(LABEL_RACE))) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_POSE);
        }
        return out;
    }

    private CatalogImage imageOf(String imageId) {
        if (imageId == null || imageId.isBlank()) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_POSE);
        }
        return imageRepository.findById(imageId).orElseThrow(() -> new CustomException(ErrorCode.CATALOG_INVALID_POSE));
    }

    private CatalogPoseUpload uploadOf(String id) {
        return uploadRepository.findById(id).orElseThrow(() -> new CustomException(ErrorCode.CATALOG_POSE_UPLOAD_NOT_FOUND));
    }

    private CatalogPoseUpload liveUploadOf(String id) {
        CatalogPoseUpload upload = uploadOf(id);
        if (upload.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_POSE_UPLOAD_NOT_FOUND);
        }
        return upload;
    }

    private CatalogPoseOverrideResponse toResponse(CatalogPoseOverride o) {
        return new CatalogPoseOverrideResponse(o.getVersion(), o.getShot(), o.isHidden() || o.isTrashed(),
                fromJson(o.getLabelsJson()), catalogService.toInstant(o.getUpdatedAt()),
                catalogService.toInstant(o.getDeletedAt()), catalogService.toInstant(o.getPurgeAt()));
    }

    private CatalogPoseUploadResponse toResponse(CatalogPoseUpload u) {
        return new CatalogPoseUploadResponse(u.getId(), catalogService.imageUrl(u.getImage().getId()), u.getWidth(), u.getHeight(),
                fromJson(u.getLabelsJson()), u.getSortOrder(), u.isHidden(),
                catalogService.toInstant(u.getCreatedAt()), catalogService.toInstant(u.getUpdatedAt()),
                catalogService.toInstant(u.getDeletedAt()), catalogService.toInstant(u.getPurgeAt()));
    }

    private String toJson(Map<String, String> labels) {
        try {
            return objectMapper.writeValueAsString(labels);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_POSE);
        }
    }

    private Map<String, String> fromJson(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, LABEL_MAP);
        } catch (IOException e) {
            return Map.of();
        }
    }
}
