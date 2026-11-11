package com.caestro.server.domain.deveval.service;

import com.caestro.server.domain.deveval.dto.PhotoScoreDto;
import com.caestro.server.domain.deveval.dto.request.UpsertDevEvaluationRequest;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationPageResponse;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationRowResponse;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationSavedResponse;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationStatsResponse;
import com.caestro.server.domain.deveval.entity.DevEvaluation;
import com.caestro.server.domain.deveval.entity.DevEvaluationGuide;
import com.caestro.server.domain.deveval.entity.DevEvaluationPhoto;
import com.caestro.server.domain.deveval.entity.DevEvaluationValues;
import com.caestro.server.domain.deveval.repository.DevEvaluationCounts;
import com.caestro.server.domain.deveval.repository.DevEvaluationGuideRepository;
import com.caestro.server.domain.deveval.repository.DevEvaluationPhotoRepository;
import com.caestro.server.domain.deveval.repository.DevEvaluationRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발자 평가 데이터 (#260, contracts/dev-evaluations.md). 앱 개발자 모드가 사진마다 매긴 만족도·가이드 유무를 기록하고,
 * 사진은 RDS 의 비공개 테이블에만 둔다(얼굴). 앱은 자기 기록만 쓰고, 열람·내려받기·지우기는 관리자 경로(스튜디오 키)뿐이다.
 */
@Service
public class DevEvaluationService {

    // 앱이 사진마다 만드는 UUID. 주소에 그대로 들어가므로 글자를 좁힌다
    static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    static final Set<String> MEDIA_TYPES = Set.of("photo", "fourcut");
    // 직접 올린 사진 가이드 이미지의 종류: 실사(오려 낸 사람)·라인 (#266)
    static final Set<String> GUIDE_KINDS = Set.of("photo", "line");
    static final int RATING_MAX_MIN = 2;
    static final int RATING_MAX_MAX = 100;
    static final int EXTRA_MAX_BYTES = 4096;
    static final int SIDE_MAX = 100_000;
    static final int DEVICE_KEY_SHORT = 16;
    static final int PAGE_SIZE_DEFAULT = 50;
    static final int PAGE_SIZE_MAX = 200;
    static final Sort NEWEST = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final DevEvaluationRepository evaluationRepository;
    private final DevEvaluationPhotoRepository photoRepository;
    private final DevEvaluationGuideRepository guideRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${dev-eval.max-per-user:3000}")
    private long maxPerUser;

    // DevEvaluationUploadSizeInterceptor 와 같은 키
    @Value("${dev-eval.max-photo-bytes:8388608}")
    private long maxPhotoBytes;

    public DevEvaluationService(DevEvaluationRepository evaluationRepository, DevEvaluationPhotoRepository photoRepository,
                                DevEvaluationGuideRepository guideRepository, UserRepository userRepository,
                                ObjectMapper objectMapper, Clock clock) {
        this.evaluationRepository = evaluationRepository;
        this.photoRepository = photoRepository;
        this.guideRepository = guideRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ── 앱 ───────────────────────────────────────────────────────────────────

    /** clientId 로 만들거나 덮어쓴다. 남의 기록이면 403, 새 기록인데 상한(3000)이면 409. */
    @Transactional
    public DevEvaluationSavedResponse upsert(Long userId, String clientId, UpsertDevEvaluationRequest request) {
        String id = validClientId(clientId);
        DevEvaluationValues values = validate(request);
        LocalDateTime now = LocalDateTime.now(clock);
        DevEvaluation existing = evaluationRepository.findById(id).orElse(null);
        if (existing != null) {
            if (!existing.isOwnedBy(userId)) {
                throw new CustomException(ErrorCode.DEV_EVAL_FORBIDDEN);
            }
            existing.apply(values, now);
            return new DevEvaluationSavedResponse(id, existing.hasPhoto());
        }
        if (evaluationRepository.countByUserId(userId) >= maxPerUser) {
            throw new CustomException(ErrorCode.DEV_EVAL_LIMIT);
        }
        evaluationRepository.save(DevEvaluation.create(id, userId, values, now));
        return new DevEvaluationSavedResponse(id, false);
    }

    /** 기록이 먼저 있어야 한다(404). 이전 사진은 바꾼다. 형식은 앞 바이트로 확인한다(JPEG·PNG·WebP). */
    @Transactional
    public void uploadPhoto(Long userId, String clientId, byte[] body, String contentType) {
        DevEvaluation evaluation = ownEvaluation(userId, clientId);
        String id = evaluation.getId();
        String type = checkedImage(body, contentType);
        // 옛 사진은 읽지 않고 지운 뒤 새로 넣는다(엔티티로 덮으면 옛 블롭을 먼저 읽어 온다)
        photoRepository.deleteQuietly(id);
        photoRepository.save(new DevEvaluationPhoto(id, type, body));
        evaluation.attachPhoto(body.length, type, LocalDateTime.now(clock));
    }

    /**
     * '직접 올린 사진' 가이드의 이미지(#266): kind 는 photo(실사) 또는 line(라인). 사진과 같은 규칙(기록 먼저, 본인만,
     * JPEG·PNG·WebP, 같은 상한)이고 같은 kind 는 바꾼다.
     */
    @Transactional
    public void uploadGuide(Long userId, String clientId, String kind, byte[] body, String contentType) {
        DevEvaluation evaluation = ownEvaluation(userId, clientId);
        String k = guideKind(kind);
        String type = checkedImage(body, contentType);
        String guideId = DevEvaluationGuide.idOf(evaluation.getId(), k);
        guideRepository.deleteQuietly(guideId);
        guideRepository.save(new DevEvaluationGuide(guideId, evaluation.getId(), k, type, body));
        evaluation.attachGuide(k, body.length, LocalDateTime.now(clock));
    }

    /** 내 기록: 기록이 먼저 있어야 하고(404) 남의 것이면 403. */
    private DevEvaluation ownEvaluation(Long userId, String clientId) {
        String id = validClientId(clientId);
        DevEvaluation evaluation = evaluationRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.DEV_EVAL_NOT_FOUND));
        if (!evaluation.isOwnedBy(userId)) {
            throw new CustomException(ErrorCode.DEV_EVAL_FORBIDDEN);
        }
        return evaluation;
    }

    /** 올린 이미지의 형식(앞 바이트로 본 것). 밝힌 형식이 이미지가 아니거나, 상한을 넘거나, JPEG·PNG·WebP 가 아니면 거른다. */
    private String checkedImage(byte[] body, String contentType) {
        String declared = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase();
        if (!(declared.isEmpty() || declared.equals("image/jpeg") || declared.equals("image/png")
                || declared.equals("image/webp") || declared.equals("application/octet-stream"))) {
            throw new CustomException(ErrorCode.DEV_EVAL_INVALID_PHOTO);
        }
        if (body != null && body.length > maxPhotoBytes) {
            throw new CustomException(ErrorCode.DEV_EVAL_PHOTO_TOO_LARGE);
        }
        String type = sniff(body);
        if (type == null) {
            throw new CustomException(ErrorCode.DEV_EVAL_INVALID_PHOTO);
        }
        return type;
    }

    static String guideKind(String kind) {
        if (kind == null || !GUIDE_KINDS.contains(kind)) {
            throw invalid("가이드 이미지 종류는 photo 또는 line 입니다");
        }
        return kind;
    }

    /** 앞 바이트로 본 형식. 셋 다 아니면 null. */
    static String sniff(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    // ── 관리자 ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DevEvaluationPageResponse page(Integer page, Integer size, Boolean guide, Long userId) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? PAGE_SIZE_DEFAULT : Math.min(size, PAGE_SIZE_MAX);
        Page<DevEvaluation> result = evaluationRepository.search(guide, userId, PageRequest.of(p, s, NEWEST));
        return new DevEvaluationPageResponse(rows(result.getContent()), p, s, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public DevEvaluationPhoto photo(String id) {
        return photoRepository.findById(id).orElseThrow(() -> new CustomException(ErrorCode.DEV_EVAL_PHOTO_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public DevEvaluationGuide guide(String id, String kind) {
        return guideRepository.findById(DevEvaluationGuide.idOf(id, guideKind(kind)))
                .orElseThrow(() -> new CustomException(ErrorCode.DEV_EVAL_GUIDE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public DevEvaluationStatsResponse stats() {
        DevEvaluationCounts c = evaluationRepository.counts();
        List<Integer> maxes = evaluationRepository.ratingMaxByUse(PageRequest.of(0, 1));
        Integer ratingMax = maxes.isEmpty() ? null : maxes.get(0);
        return new DevEvaluationStatsResponse(c.count(), c.users(), c.withPhoto(),
                new DevEvaluationStatsResponse.Group(c.guideCount(), scaled(c.guideRatio(), ratingMax)),
                new DevEvaluationStatsResponse.Group(c.noGuideCount(), scaled(c.noGuideRatio(), ratingMax)),
                ratingMax);
    }

    private static Double scaled(Double ratio, Integer ratingMax) {
        if (ratio == null || ratingMax == null) {
            return null;
        }
        return Math.round(ratio * ratingMax * 100.0) / 100.0;
    }

    /** 지운다(사진·가이드 이미지도). 없으면 404. */
    @Transactional
    public void delete(String id) {
        DevEvaluation evaluation = evaluationRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.DEV_EVAL_NOT_FOUND));
        photoRepository.deleteQuietly(id);
        guideRepository.deleteByEvaluation(id);
        evaluationRepository.delete(evaluation);
    }

    /** 기록 전부를 CSV 로(사진 없음). 엑셀이 한국어를 바로 읽도록 UTF-8 BOM 으로 시작하고 줄은 CRLF. */
    @Transactional(readOnly = true)
    public byte[] csv(Boolean guide, Long userId) {
        List<DevEvaluationRowResponse> rows = rows(evaluationRepository.searchAll(guide, userId, NEWEST));
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", DevEvaluationCsv.HEADER)).append("\r\n");
        for (DevEvaluationRowResponse row : rows) {
            sb.append(DevEvaluationCsv.line(row, this::json)).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private List<DevEvaluationRowResponse> rows(List<DevEvaluation> evaluations) {
        Set<Long> userIds = evaluations.stream().map(DevEvaluation::getUserId).collect(Collectors.toSet());
        Map<Long, User> users = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<DevEvaluationRowResponse> out = new ArrayList<>(evaluations.size());
        for (DevEvaluation e : evaluations) {
            out.add(toRow(e, users.get(e.getUserId())));
        }
        return out;
    }

    private DevEvaluationRowResponse toRow(DevEvaluation e, User user) {
        PhotoScoreDto score = e.getScoreTotal() == null && e.getScorePose() == null && e.getScorePlace() == null && e.getScoreShape() == null
                ? null : new PhotoScoreDto(e.getScoreTotal(), e.getScorePose(), e.getScorePlace(), e.getScoreShape());
        String deviceKey = user == null || user.getDeviceKey() == null ? null
                : user.getDeviceKey().substring(0, Math.min(DEVICE_KEY_SHORT, user.getDeviceKey().length()));
        return new DevEvaluationRowResponse(e.getId(), e.getUserId(), user == null ? null : user.getNickname(), deviceKey,
                e.getRating(), e.getRatingMax(), e.isGuide(), e.getGuideVersion(), e.getGuideShot(), e.getGuideStyle(),
                e.getShutterScore(), score, e.getMediaType(), toInstant(e.getTakenAt()), toInstant(e.getRatedAt()),
                e.getWidth(), e.getHeight(), e.getAppVersion(), e.getDevice(), e.getNote(), extra(e.getExtraJson()),
                e.hasPhoto(), e.getPhotoBytes(), e.guideImages(), toInstant(e.getCreatedAt()), toInstant(e.getUpdatedAt()));
    }

    // ── 검증·변환 ─────────────────────────────────────────────────────────────

    static String validClientId(String clientId) {
        if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
            throw new CustomException(ErrorCode.DEV_EVAL_INVALID, "clientId 는 영문·숫자·-·_ 8~64자(UUID)여야 합니다");
        }
        return clientId;
    }

    private DevEvaluationValues validate(UpsertDevEvaluationRequest r) {
        if (r == null) {
            throw invalid("본문이 없습니다");
        }
        if (r.ratingMax() == null || r.ratingMax() < RATING_MAX_MIN || r.ratingMax() > RATING_MAX_MAX) {
            throw invalid("ratingMax 는 " + RATING_MAX_MIN + "~" + RATING_MAX_MAX + " 사이 정수여야 합니다");
        }
        if (r.rating() == null || r.rating() < 1 || r.rating() > r.ratingMax()) {
            throw invalid("rating 은 1~ratingMax(" + r.ratingMax() + ") 사이 정수여야 합니다");
        }
        if (r.guide() == null) {
            throw invalid("guide(가이드를 띄우고 찍었는지)는 필수입니다");
        }
        String mediaType = r.mediaType() == null ? null : r.mediaType().strip();
        if (mediaType == null || !MEDIA_TYPES.contains(mediaType)) {
            throw invalid("mediaType 은 photo 또는 fourcut 이어야 합니다");
        }
        if (r.shutterScore() != null && !inRange(r.shutterScore(), 0, 100)) {
            throw invalid("shutterScore 는 0~100 사이 숫자여야 합니다");
        }
        PhotoScoreDto score = r.photoScore();
        if (score != null && !(finiteOrNull(score.total()) && finiteOrNull(score.pose())
                && finiteOrNull(score.place()) && finiteOrNull(score.shape()))) {
            throw invalid("photoScore 의 값은 숫자 또는 null 이어야 합니다");
        }
        if (r.width() != null && (r.width() < 1 || r.width() > SIDE_MAX)) {
            throw invalid("width 는 1~" + SIDE_MAX + " 사이 정수여야 합니다");
        }
        if (r.height() != null && (r.height() < 1 || r.height() > SIDE_MAX)) {
            throw invalid("height 는 1~" + SIDE_MAX + " 사이 정수여야 합니다");
        }
        return new DevEvaluationValues(
                r.rating(), r.ratingMax(), r.guide(),
                text("guideVersion", r.guideVersion(), 40),
                text("guideShot", r.guideShot(), 80),
                text("guideStyle", r.guideStyle(), 20),
                r.shutterScore(),
                score == null ? null : score.total(),
                score == null ? null : score.pose(),
                score == null ? null : score.place(),
                score == null ? null : score.shape(),
                mediaType,
                time("takenAt", r.takenAt(), false),
                time("ratedAt", r.ratedAt(), true),
                r.width(), r.height(),
                text("appVersion", r.appVersion(), 40),
                text("device", r.device(), 80),
                text("note", r.note(), 500),
                extraJson(r.extra()));
    }

    private static String text(String field, String value, int max) {
        if (value == null) {
            return null;
        }
        String s = value.strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > max) {
            throw invalid(field + " 는 " + max + "자까지입니다");
        }
        return s;
    }

    /** ISO-8601(2026-11-10T07:12:00Z, +09:00 같은 오프셋 포함)을 서버 시계 시간대의 LocalDateTime 으로. */
    private LocalDateTime time(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw invalid(field + " 는 필수입니다 (ISO-8601, 예: 2026-11-10T07:30:00Z)");
            }
            return null;
        }
        Instant instant;
        try {
            instant = OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                instant = Instant.parse(value.strip());
            } catch (DateTimeParseException e2) {
                throw invalid(field + " 는 ISO-8601 시각이어야 합니다 (예: 2026-11-10T07:30:00Z)");
            }
        }
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }

    private String extraJson(Map<String, Object> extra) {
        if (extra == null) {
            return null;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(extra);
        } catch (JsonProcessingException e) {
            throw invalid("extra 를 JSON 으로 쓸 수 없습니다");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > EXTRA_MAX_BYTES) {
            throw invalid("extra 는 " + EXTRA_MAX_BYTES / 1024 + "KB 이하 JSON 객체여야 합니다");
        }
        return json;
    }

    private Map<String, Object> extra(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (IOException e) {
            return null;
        }
    }

    private String json(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean inRange(double v, double min, double max) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v >= min && v <= max;
    }

    private static boolean finiteOrNull(Double v) {
        return v == null || (!v.isNaN() && !v.isInfinite());
    }

    private static CustomException invalid(String detail) {
        return new CustomException(ErrorCode.DEV_EVAL_INVALID, detail);
    }

    private Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.atZone(clock.getZone()).toInstant();
    }
}
