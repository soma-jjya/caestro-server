package com.caestro.server.domain.catalog.service;

import com.caestro.server.domain.catalog.dto.request.UpsertRecommendRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendFileResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendManifest;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendResponse;
import com.caestro.server.domain.catalog.dto.response.RecommendFileDto;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.entity.CatalogRecommendConfig;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.domain.catalog.repository.CatalogRecommendConfigRepository;
import com.caestro.server.domain.catalog.store.CatalogAssetStore;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포즈추천 알고리즘 설정 (#260, contracts/recommend-config.md). 앱에 상수로 박혀 있던 장소 읽기 문턱값·점수 가중치·장소→풀 매핑과
 * 장소 헤드·모델을 프레임·배너처럼 스튜디오에서 바꾼다. 버전을 여러 개 만들고 하나를 게시하면 앱이 {@code GET /catalog/recommend} 로 받는다.
 * 게시를 내리면 앱은 번들 기본값으로 돌아간다. 헤드·모델 파일은 그림과 같은 자산 저장소(운영 S3+CDN)와 {@code catalog_images} 행에 둔다.
 */
@Service
public class CatalogRecommendService {

    static final String KIND_HEAD = "head";
    static final String KIND_MODEL = "model";
    static final String HEAD_TYPE = "application/json";
    static final String MODEL_TYPE = "application/octet-stream";
    static final int NAME_MAX = 60;
    static final int NOTE_MAX = 500;
    private static final TypeReference<LinkedHashMap<String, Object>> PARAMS_MAP = new TypeReference<>() {
    };

    private final CatalogRecommendConfigRepository configRepository;
    private final CatalogImageRepository imageRepository;
    private final CatalogAssetStore assetStore;
    private final CatalogIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // RecommendFileSizeInterceptor 와 같은 키 — 본문 전 선검사와 여기의 상한이 어긋나지 않게
    @Value("${catalog.recommend.max-head-bytes:5242880}")
    private long maxHeadBytes;

    @Value("${catalog.recommend.max-model-bytes:33554432}")
    private long maxModelBytes;

    public CatalogRecommendService(CatalogRecommendConfigRepository configRepository, CatalogImageRepository imageRepository,
                                   CatalogAssetStore assetStore, CatalogIdGenerator idGenerator,
                                   ObjectMapper objectMapper, Clock clock) {
        this.configRepository = configRepository;
        this.imageRepository = imageRepository;
        this.assetStore = assetStore;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ── 앱(공개) ─────────────────────────────────────────────────────────────

    /** 게시본. 없으면 비어 있다(앱은 번들 기본값). */
    @Transactional(readOnly = true)
    public Optional<CatalogRecommendManifest> manifest() {
        return configRepository.findFirstByActiveTrueOrderByPublishedAtDesc()
                .map(c -> new CatalogRecommendManifest(c.getId(), c.getName(), toInstant(c.getPublishedAt()), params(c),
                        file(c.getHead()), file(c.getModel())));
    }

    /**
     * 약한 ETag: 게시한 버전 id 와 게시 시각. 게시·다시 게시·롤백(옛 버전 게시)·게시 내리기마다 바뀐다.
     * 게시 중인 버전은 고칠 수 없으므로 이 둘이 같으면 내용도 같다.
     */
    public static String etag(Optional<CatalogRecommendManifest> manifest) {
        return manifest.map(m -> "W/\"rc-" + m.id() + "-" + (m.updatedAt() == null ? 0 : m.updatedAt().toEpochMilli()) + "\"")
                .orElse("W/\"rc-none\"");
    }

    // ── 스튜디오 ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CatalogRecommendListResponse list() {
        String activeId = configRepository.findFirstByActiveTrueOrderByPublishedAtDesc().map(CatalogRecommendConfig::getId).orElse(null);
        List<CatalogRecommendResponse> items = configRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
        return new CatalogRecommendListResponse(activeId, items);
    }

    @Transactional
    public CatalogRecommendResponse create(UpsertRecommendRequest request) {
        Valid v = validate(request);
        LocalDateTime now = LocalDateTime.now(clock);
        CatalogRecommendConfig config = configRepository.save(CatalogRecommendConfig.builder()
                .id(idGenerator.recommendId())
                .name(v.name).note(v.note).paramsJson(v.paramsJson)
                .head(v.head).model(v.model)
                .active(false)
                .createdAt(now).updatedAt(now)
                .build());
        return toResponse(config);
    }

    /** 통째로 바꾼다. 게시 중이면 409 — 앱이 받은 내용과 서버의 내용이 어긋나지 않게(스튜디오가 복제해서 고친다). */
    @Transactional
    public CatalogRecommendResponse update(String id, UpsertRecommendRequest request) {
        CatalogRecommendConfig config = configOf(id);
        if (config.isActive()) {
            throw new CustomException(ErrorCode.RECOMMEND_ACTIVE_LOCKED);
        }
        Valid v = validate(request);
        CatalogImage previousHead = config.getHead();
        CatalogImage previousModel = config.getModel();
        config.update(v.name, v.note, v.paramsJson, v.head, v.model, LocalDateTime.now(clock));
        // 바뀌어 아무 버전도 안 쓰게 된 파일은 지운다(프레임 그림 교체와 같다). 복제한 버전이 쓰고 있으면 남긴다
        if (previousHead != null && !sameFile(previousHead, v.head)) {
            deleteIfUnused(config.getId(), previousHead);
        }
        if (previousModel != null && !sameFile(previousModel, v.model)) {
            deleteIfUnused(config.getId(), previousModel);
        }
        return toResponse(config);
    }

    /** 이 버전만 게시한다(나머지는 모두 내림). 옛 버전을 다시 게시하면 그게 롤백이다. */
    @Transactional
    public CatalogRecommendResponse publish(String id) {
        configOf(id);   // 없는 id 로 게시를 내리지 않게 먼저 본다
        configRepository.deactivateAll();
        CatalogRecommendConfig config = configOf(id);   // 위 UPDATE 가 영속성 컨텍스트를 비웠다
        config.publish(LocalDateTime.now(clock));
        return toResponse(config);
    }

    /** 게시를 내린다. 앱은 번들 기본값으로 돌아간다. 게시본이 없어도 204. */
    @Transactional
    public void unpublish() {
        configRepository.deactivateAll();
    }

    /** 지운다(휴지통 없음). 게시 중이면 409. 아무 버전도 안 쓰게 된 헤드·모델 파일도 지운다. */
    @Transactional
    public void delete(String id) {
        CatalogRecommendConfig config = configOf(id);
        if (config.isActive()) {
            throw new CustomException(ErrorCode.RECOMMEND_ACTIVE_LOCKED);
        }
        CatalogImage head = config.getHead();
        CatalogImage model = config.getModel();
        configRepository.delete(config);
        if (head != null) {
            deleteIfUnused(id, head);
        }
        if (model != null && !sameFile(model, head)) {
            deleteIfUnused(id, model);
        }
    }

    /**
     * 헤드 JSON(kind=head, application/json, 모양 검증) 또는 ONNX 모델(kind=model, application/octet-stream, 비어 있지 않음)을 저장한다.
     * 메타 행은 catalog_images(폭·높이 0), 바이트는 자산 저장소 — 그림과 같은 길이라 주소·캐시·고아 정리도 같다.
     */
    @Transactional
    public CatalogRecommendFileResponse storeFile(String kind, byte[] body, String contentType) {
        String type = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase();
        if (KIND_HEAD.equals(kind)) {
            if (!HEAD_TYPE.equals(type)) {
                throw new CustomException(ErrorCode.RECOMMEND_HEAD_INVALID, "Content-Type 은 application/json 이어야 합니다");
            }
            if (body == null || body.length == 0) {
                throw new CustomException(ErrorCode.RECOMMEND_HEAD_INVALID, "본문이 비어 있습니다");
            }
            if (body.length > maxHeadBytes) {
                throw new CustomException(ErrorCode.RECOMMEND_FILE_TOO_LARGE);
            }
            RecommendHeadValidator.validate(body, objectMapper);
        } else if (KIND_MODEL.equals(kind)) {
            if (!MODEL_TYPE.equals(type)) {
                throw new CustomException(ErrorCode.RECOMMEND_MODEL_INVALID, "Content-Type 은 application/octet-stream 이어야 합니다");
            }
            if (body == null || body.length == 0) {
                throw new CustomException(ErrorCode.RECOMMEND_MODEL_INVALID, "본문이 비어 있습니다");
            }
            if (body.length > maxModelBytes) {
                throw new CustomException(ErrorCode.RECOMMEND_FILE_TOO_LARGE);
            }
        } else {
            throw new CustomException(ErrorCode.RECOMMEND_INVALID, "kind 는 head 또는 model 이어야 합니다");
        }
        CatalogImage file = imageRepository.save(CatalogImage.builder()
                .id(idGenerator.imageId())
                .contentType(type)
                .sizeBytes(body.length)
                .width(0)
                .height(0)
                .createdAt(LocalDateTime.now(clock))
                .build());
        assetStore.put(file.getId(), type, body);
        return new CatalogRecommendFileResponse(file.getId(), assetStore.urlOf(file.getId()), body.length, kind);
    }

    // ── 검증·변환 ─────────────────────────────────────────────────────────────

    private record Valid(String name, String note, String paramsJson, CatalogImage head, CatalogImage model) {
    }

    private Valid validate(UpsertRecommendRequest request) {
        if (request == null) {
            throw new CustomException(ErrorCode.RECOMMEND_INVALID, "본문이 없습니다");
        }
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new CustomException(ErrorCode.RECOMMEND_INVALID, "name 은 1~" + NAME_MAX + "자여야 합니다");
        }
        String note = request.note() == null ? null : request.note().strip();
        if (note != null && note.length() > NOTE_MAX) {
            throw new CustomException(ErrorCode.RECOMMEND_INVALID, "note 는 " + NOTE_MAX + "자까지입니다");
        }
        Map<String, Object> params = RecommendParams.normalize(request.params());
        CatalogImage head = fileOf(request.headId(), HEAD_TYPE, "headId");
        CatalogImage model = fileOf(request.modelId(), MODEL_TYPE, "modelId");
        return new Valid(name, note == null || note.isEmpty() ? null : note, toJson(params), head, model);
    }

    /** null·빈 값이면 앱 번들. 아니면 그 종류로 올린 파일이어야 한다. */
    private CatalogImage fileOf(String id, String type, String field) {
        if (id == null || id.isBlank()) {
            return null;
        }
        CatalogImage file = imageRepository.findById(id.strip()).orElse(null);
        if (file == null || !type.equals(file.getContentType())) {
            throw new CustomException(ErrorCode.RECOMMEND_INVALID,
                    field + " 가 /catalog/studio/recommend/files?kind=" + (HEAD_TYPE.equals(type) ? KIND_HEAD : KIND_MODEL) + " 로 올린 파일이 아닙니다");
        }
        return file;
    }

    private void deleteIfUnused(String configId, CatalogImage file) {
        if (configRepository.countOtherUses(configId, file) == 0) {
            imageRepository.delete(file);
            assetStore.delete(file.getId());
        }
    }

    private static boolean sameFile(CatalogImage a, CatalogImage b) {
        return a != null && b != null && a.getId().equals(b.getId());
    }

    private CatalogRecommendConfig configOf(String id) {
        return configRepository.findById(id).orElseThrow(() -> new CustomException(ErrorCode.RECOMMEND_NOT_FOUND));
    }

    private CatalogRecommendResponse toResponse(CatalogRecommendConfig c) {
        return new CatalogRecommendResponse(c.getId(), c.getName(), c.getNote(), params(c), file(c.getHead()), file(c.getModel()),
                c.isActive(), toInstant(c.getCreatedAt()), toInstant(c.getUpdatedAt()), toInstant(c.getPublishedAt()));
    }

    private RecommendFileDto file(CatalogImage f) {
        return f == null ? null : new RecommendFileDto(f.getId(), assetStore.urlOf(f.getId()), f.getSizeBytes());
    }

    private Map<String, Object> params(CatalogRecommendConfig c) {
        try {
            return objectMapper.readValue(c.getParamsJson(), PARAMS_MAP);
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.RECOMMEND_INVALID);
        }
    }

    private Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.atZone(clock.getZone()).toInstant();
    }
}
