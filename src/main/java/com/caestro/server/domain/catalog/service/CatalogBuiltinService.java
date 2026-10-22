package com.caestro.server.domain.catalog.service;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.dto.LocalizedText;
import com.caestro.server.domain.catalog.dto.request.UpsertBuiltinFrameRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogBuiltinFrameResponse;
import com.caestro.server.domain.catalog.entity.CatalogBuiltinFrame;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.repository.CatalogBuiltinFrameRepository;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱에 들어 있는 기본 프레임을 스튜디오에서 덮어쓴다(카탈로그 5차, contracts/catalog-studio.md).
 * 이름·한 줄 소개·Pro·배지·숨김은 모든 기본 프레임, 그림·창은 AI 프레임만. 덮어쓰지 않은 값은 앱에 든 그대로다.
 */
@Service
@RequiredArgsConstructor
public class CatalogBuiltinService {

    /** 앱에 들어 있는 기본 프레임 id → 종류(base: 단색, art: AI 프레임). 앱·스튜디오의 id 와 같다 */
    static final Map<String, String> BUILTINS = Map.of(
            "Black", "base", "White", "base",
            "gyaru", "art", "street", "art", "chrome", "art", "birthday", "art",
            "picnic", "art", "patchwork", "art", "vintage", "art");

    private static final TypeReference<List<FrameWindowDto>> WINDOW_LIST = new TypeReference<>() {
    };

    private final CatalogBuiltinFrameRepository repository;
    private final CatalogImageRepository imageRepository;
    private final CatalogService catalogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 덮어쓴 기본 프레임 전부(숨김·휴지통 포함 — 앱이 목록에서 빼야 하므로). */
    @Transactional(readOnly = true)
    public List<CatalogBuiltinFrameResponse> all() {
        return repository.findAllByOrderByIdAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogBuiltinFrameResponse> trashed() {
        return repository.findAllByDeletedAtIsNotNullOrderByDeletedAtDesc().stream().map(this::toResponse).toList();
    }

    /** 보낸 필드만 덮어쓴다. 처음이면 행을 만든다. */
    @Transactional
    public CatalogBuiltinFrameResponse update(String id, UpsertBuiltinFrameRequest request) {
        boolean art = "art".equals(kindOf(id));
        LocalDateTime now = LocalDateTime.now(clock);
        CatalogBuiltinFrame row = repository.findById(id)
                .orElseGet(() -> CatalogBuiltinFrame.builder().id(id).updatedAt(now).build());
        if (request.title() != null) {
            LocalizedText t = CatalogService.requireText(request.title(), 40, false, ErrorCode.CATALOG_INVALID_FRAME);
            row.setTitle(t == null ? null : t.ko(), t == null ? null : t.en());
        }
        if (request.subtitle() != null) {
            LocalizedText s = CatalogService.requireText(request.subtitle(), 80, false, ErrorCode.CATALOG_INVALID_FRAME);
            row.setSubtitle(s == null ? null : s.ko(), s == null ? null : s.en());
        }
        if (request.badge() != null) {
            LocalizedText b = CatalogService.requireText(request.badge(), CatalogService.BADGE_MAX, false, ErrorCode.CATALOG_INVALID_FRAME);
            row.setBadge(b == null ? null : b.ko(), b == null ? null : b.en());
        }
        if (request.pro() != null) {
            row.setPro(request.pro());
        }
        if (request.hidden() != null) {
            row.setHidden(request.hidden());
        }
        if (Boolean.TRUE.equals(request.resetArtwork())) {
            row.setArtwork(null, null);
        }
        if (request.imageId() != null || request.windows() != null) {
            // 단색 프레임에는 그림이 없다
            if (!art) {
                throw new CustomException(ErrorCode.CATALOG_INVALID_FRAME);
            }
            List<FrameWindowDto> windows = request.windows() == null ? null : CatalogService.validWindows(request.windows());
            if (request.windows() != null && windows == null) {
                throw new CustomException(ErrorCode.CATALOG_INVALID_FRAME);
            }
            if (request.imageId() != null) {
                // 새 그림은 창 4개와 함께 온다(앱에 든 그림의 창은 앱이 그림에서 찾으므로 다른 그림에 맞지 않는다)
                if (windows == null) {
                    throw new CustomException(ErrorCode.CATALOG_INVALID_FRAME);
                }
                CatalogImage image = imageRepository.findById(request.imageId())
                        .orElseThrow(() -> new CustomException(ErrorCode.CATALOG_INVALID_FRAME));
                row.setArtwork(image, toJson(windows));
            } else {
                // 창만 고치기는 이미 바꾼 그림에서만
                if (row.getImage() == null) {
                    throw new CustomException(ErrorCode.CATALOG_INVALID_FRAME);
                }
                row.setWindowsJson(toJson(windows));
            }
        }
        row.touch(now);
        return toResponse(repository.save(row));
    }

    /** 휴지통으로: 앱에서 빠진다. 기본 프레임은 정리 배치가 지우지 않으니 되살릴 때까지 휴지통에 남는다. */
    @Transactional
    public void delete(String id) {
        kindOf(id);
        LocalDateTime now = LocalDateTime.now(clock);
        CatalogBuiltinFrame row = repository.findById(id)
                .orElseGet(() -> CatalogBuiltinFrame.builder().id(id).updatedAt(now).build());
        if (row.isTrashed()) {
            throw new CustomException(ErrorCode.CATALOG_FRAME_NOT_FOUND);
        }
        row.moveToTrash(now);
        repository.save(row);
    }

    @Transactional
    public CatalogBuiltinFrameResponse restore(String id) {
        kindOf(id);
        CatalogBuiltinFrame row = repository.findById(id)
                .filter(CatalogBuiltinFrame::isTrashed)
                .orElseThrow(() -> new CustomException(ErrorCode.CATALOG_NOT_IN_TRASH));
        row.restore(LocalDateTime.now(clock));
        return toResponse(row);
    }

    /** 원래대로: 덮어쓴 값을 모두 지워 앱에 든 그대로 돌아간다(휴지통에 있었으면 되살아난다). */
    @Transactional
    public void reset(String id) {
        kindOf(id);
        repository.findById(id).ifPresent(repository::delete);
    }

    private String kindOf(String id) {
        String kind = id == null ? null : BUILTINS.get(id);
        if (kind == null) {
            throw new CustomException(ErrorCode.CATALOG_BUILTIN_NOT_FOUND);
        }
        return kind;
    }

    CatalogBuiltinFrameResponse toResponse(CatalogBuiltinFrame row) {
        CatalogImage image = row.getImage();
        return new CatalogBuiltinFrameResponse(
                row.getId(),
                BUILTINS.get(row.getId()),
                LocalizedText.of(row.getTitleKo(), row.getTitleEn()),
                LocalizedText.of(row.getSubtitleKo(), row.getSubtitleEn()),
                row.getPro(),
                LocalizedText.of(row.getBadgeKo(), row.getBadgeEn()),
                row.isHidden() || row.isTrashed(),
                catalogService.toInstant(row.getDeletedAt()),
                image == null ? null : catalogService.imageUrl(image.getId()),
                image == null ? null : row.getWidth(),
                image == null ? null : row.getHeight(),
                image == null ? null : fromJson(row.getWindowsJson()),
                catalogService.toInstant(row.getUpdatedAt()));
    }

    private String toJson(List<FrameWindowDto> windows) {
        try {
            return objectMapper.writeValueAsString(windows);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.CATALOG_INVALID_FRAME);
        }
    }

    private List<FrameWindowDto> fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, WINDOW_LIST);
        } catch (IOException e) {
            return null;
        }
    }
}
