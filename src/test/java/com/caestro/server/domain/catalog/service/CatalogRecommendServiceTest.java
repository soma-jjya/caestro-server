package com.caestro.server.domain.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.catalog.dto.request.UpsertRecommendRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendFileResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendManifest;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendResponse;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.entity.CatalogRecommendConfig;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.domain.catalog.repository.CatalogRecommendConfigRepository;
import com.caestro.server.domain.catalog.store.CatalogAssetStore;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 추천 설정(contracts/recommend-config.md): params 범위·정수·모르는 키, 게시 중 잠금, 게시는 하나만, 헤드 JSON 모양 검증,
 * 파일 종류·상한, 아무 버전도 안 쓰게 된 파일 정리, 게시본과 ETag.
 */
@ExtendWith(MockitoExtension.class)
class CatalogRecommendServiceTest {

    @Mock private CatalogRecommendConfigRepository configRepository;
    @Mock private CatalogImageRepository imageRepository;
    @Mock private CatalogAssetStore assetStore;
    @Mock private CatalogIdGenerator idGenerator;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CatalogRecommendService service;

    @BeforeEach
    void setUp() {
        service = new CatalogRecommendService(configRepository, imageRepository, assetStore, idGenerator, objectMapper, clock);
        ReflectionTestUtils.setField(service, "maxHeadBytes", 5L * 1024 * 1024);
        ReflectionTestUtils.setField(service, "maxModelBytes", 64L);
        lenient().when(assetStore.urlOf(anyString())).thenAnswer(inv -> "https://cdn.test/catalog/" + inv.getArgument(0));
        lenient().when(configRepository.save(any(CatalogRecommendConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(imageRepository.save(any(CatalogImage.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(idGenerator.recommendId()).thenReturn("rc_abcdefghij");
        lenient().when(idGenerator.imageId()).thenReturn("file00000000000000000A");
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private CatalogImage file(String id, String type) {
        return CatalogImage.builder().id(id).contentType(type).sizeBytes(81234).width(0).height(0)
                .createdAt(LocalDateTime.now(clock)).build();
    }

    private CatalogRecommendConfig config(String id, boolean active, CatalogImage head) {
        LocalDateTime t = LocalDateTime.now(clock).minusDays(1);
        return CatalogRecommendConfig.builder().id(id).name("기본").paramsJson("{}").head(head).active(active)
                .createdAt(t).updatedAt(t).publishedAt(active ? t : null).build();
    }

    private static void assertRejected(Runnable call, ErrorCode code, String detailPart) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(code);
                    if (detailPart != null) {
                        assertThat(ce.responseMessage()).contains(detailPart);
                    }
                });
    }

    // ── params ──

    @Test
    @DisplayName("params 는 정해진 순서로 정리되고 정수로 떨어지는 값은 정수로 남는다")
    void params_normalized() {
        Map<String, Object> params = map(
                "placeToPool", map("거리", List.of("거리", " 계단 ", "거리")),
                "scoring", map("listSize", 40, "bgPoseWeight", 25.0),
                "place", map("intervalMs", 1000.0, "sure", 0.55, "switchMargin", 0.1, "halfLifeMs", 1500, "staleMs", null));

        CatalogRecommendResponse res = service.create(new UpsertRecommendRequest(" 기본 + 거리→계단 ", "", params, null, null));

        assertThat(res.id()).isEqualTo("rc_abcdefghij");
        assertThat(res.name()).isEqualTo("기본 + 거리→계단");
        assertThat(res.note()).isNull();
        assertThat(res.active()).isFalse();
        assertThat(res.head()).isNull();
        assertThat(res.params().keySet()).containsExactly("place", "scoring", "placeToPool");
        @SuppressWarnings("unchecked") Map<String, Object> place = (Map<String, Object>) res.params().get("place");
        assertThat(place.keySet()).as("null 은 빠지고 순서는 정해진 대로").containsExactly("sure", "halfLifeMs", "switchMargin", "intervalMs");
        assertThat(((Number) place.get("intervalMs")).longValue()).isEqualTo(1000L);
        assertThat(place.get("intervalMs")).isNotInstanceOf(Double.class);
        assertThat(place.get("sure")).isEqualTo(0.55);
        @SuppressWarnings("unchecked") Map<String, Object> scoring = (Map<String, Object>) res.params().get("scoring");
        assertThat(scoring.get("bgPoseWeight")).as("25.0 은 25 로").isNotInstanceOf(Double.class);
        assertThat(res.params().get("placeToPool")).isEqualTo(map("거리", List.of("거리", "계단")));
    }

    @Test
    @DisplayName("params 가 없거나 비면 {} 로 저장한다")
    void params_empty() {
        CatalogRecommendResponse res = service.create(new UpsertRecommendRequest("빈 설정", null, null, null, null));

        assertThat(res.params()).isEmpty();
        ArgumentCaptor<CatalogRecommendConfig> saved = ArgumentCaptor.forClass(CatalogRecommendConfig.class);
        verify(configRepository).save(saved.capture());
        assertThat(saved.getValue().getParamsJson()).isEqualTo("{}");
    }

    @Test
    @DisplayName("모르는 키는 어디에 있든 400 이고 그 키 이름을 알려준다")
    void params_unknownKeys() {
        assertRejected(() -> service.create(new UpsertRecommendRequest("x", null, map("places", map()), null, null)),
                ErrorCode.RECOMMEND_INVALID, "places");
        assertRejected(() -> service.create(new UpsertRecommendRequest("x", null, map("place", map("sur", 0.5)), null, null)),
                ErrorCode.RECOMMEND_INVALID, "sur");
        assertRejected(() -> service.create(new UpsertRecommendRequest("x", null, map("scoring", map("listsize", 40)), null, null)),
                ErrorCode.RECOMMEND_INVALID, "listsize");
        verify(configRepository, never()).save(any());
    }

    @Test
    @DisplayName("범위를 벗어나거나 정수 자리에 소수·문자가 오면 400")
    void params_ranges() {
        List<Map<String, Object>> bad = new ArrayList<>(List.of(
                map("place", map("sure", 1.2)),
                map("place", map("sure", -0.1)),
                map("place", map("halfLifeMs", 99)),
                map("place", map("halfLifeMs", 60_001)),
                map("place", map("halfLifeMs", 1500.5)),
                map("place", map("switchMargin", 2)),
                map("place", map("staleMs", 999)),
                map("place", map("staleMs", 600_001)),
                map("place", map("intervalMs", 249)),
                map("place", map("intervalMs", 10_001)),
                map("place", map("sure", "0.5")),
                map("scoring", map("bgPoseWeight", 1000.5)),
                map("scoring", map("posturePenalty", -1)),
                map("scoring", map("listSize", 0)),
                map("scoring", map("listSize", 201)),
                map("scoring", map("listSize", 40.5)),
                map("place", 0.5),
                map("scoring", List.of())));
        for (Map<String, Object> params : bad) {
            assertRejected(() -> service.create(new UpsertRecommendRequest("x", null, params, null, null)),
                    ErrorCode.RECOMMEND_INVALID, null);
        }
        assertRejected(() -> service.create(new UpsertRecommendRequest("x", null, map("place", map("halfLifeMs", 1500.5)), null, null)),
                ErrorCode.RECOMMEND_INVALID, "params.place.halfLifeMs 는 100~60000 사이 정수여야 합니다 (받은 값: 1500.5)");
        // 경계값은 통과
        service.create(new UpsertRecommendRequest("x", null, map(
                "place", map("sure", 0, "halfLifeMs", 100, "switchMargin", 1, "staleMs", 600_000, "intervalMs", 250),
                "scoring", map("bgPoseWeight", 1000, "posturePenalty", 0, "listSize", 200)), null, null));
    }

    @Test
    @DisplayName("placeToPool: 장소 20개·값 20개·글자 1..20, 빈 목록·문자열 아닌 값은 400")
    void params_placeToPool() {
        Map<String, Object> tooMany = new LinkedHashMap<>();
        for (int i = 0; i < 21; i++) {
            tooMany.put("장소" + i, List.of("거리"));
        }
        List<String> tooManyValues = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooManyValues.add("v" + i);
        }
        List<Map<String, Object>> bad = List.of(
                map("placeToPool", tooMany),
                map("placeToPool", map("거리", tooManyValues)),
                map("placeToPool", map("거리", List.of())),
                map("placeToPool", map("거리", "계단")),
                map("placeToPool", map("거리", List.of("가".repeat(21)))),
                map("placeToPool", map("가".repeat(21), List.of("거리"))),
                map("placeToPool", map("", List.of("거리"))),
                map("placeToPool", map("거리", List.of(3))));
        for (Map<String, Object> params : bad) {
            assertRejected(() -> service.create(new UpsertRecommendRequest("x", null, params, null, null)),
                    ErrorCode.RECOMMEND_INVALID, "placeToPool");
        }
    }

    @Test
    @DisplayName("name 1..60자, note 500자, headId·modelId 는 그 종류로 올린 파일이어야 한다")
    void request_fields() {
        assertRejected(() -> service.create(new UpsertRecommendRequest("  ", null, null, null, null)), ErrorCode.RECOMMEND_INVALID, "name");
        assertRejected(() -> service.create(new UpsertRecommendRequest("가".repeat(61), null, null, null, null)), ErrorCode.RECOMMEND_INVALID, "name");
        assertRejected(() -> service.create(new UpsertRecommendRequest("x", "가".repeat(501), null, null, null)), ErrorCode.RECOMMEND_INVALID, "note");

        given(imageRepository.findById("png")).willReturn(Optional.of(file("png", "image/png")));
        given(imageRepository.findById("model")).willReturn(Optional.of(file("model", "application/octet-stream")));
        assertRejected(() -> service.create(new UpsertRecommendRequest("x", null, null, "png", null)), ErrorCode.RECOMMEND_INVALID, "headId");
        assertRejected(() -> service.create(new UpsertRecommendRequest("x", null, null, "model", null)), ErrorCode.RECOMMEND_INVALID, "headId");
        assertRejected(() -> service.create(new UpsertRecommendRequest("x", null, null, "nope", null)), ErrorCode.RECOMMEND_INVALID, "headId");
        assertRejected(() -> service.create(new UpsertRecommendRequest("x", null, null, null, "png")), ErrorCode.RECOMMEND_INVALID, "modelId");

        CatalogRecommendResponse ok = service.create(new UpsertRecommendRequest("x", null, null, null, "model"));
        assertThat(ok.model().id()).isEqualTo("model");
        assertThat(ok.model().url()).isEqualTo("https://cdn.test/catalog/model");
        assertThat(ok.model().bytes()).isEqualTo(81234);
    }

    // ── 잠금·게시 ──

    @Test
    @DisplayName("게시 중인 버전은 고치기·지우기 모두 409 RECOMMEND_ACTIVE_LOCKED")
    void active_isLocked() {
        given(configRepository.findById("rc_live")).willReturn(Optional.of(config("rc_live", true, null)));

        assertRejected(() -> service.update("rc_live", new UpsertRecommendRequest("새 이름", null, null, null, null)),
                ErrorCode.RECOMMEND_ACTIVE_LOCKED, null);
        assertRejected(() -> service.delete("rc_live"), ErrorCode.RECOMMEND_ACTIVE_LOCKED, null);
        verify(configRepository, never()).delete(any());
    }

    @Test
    @DisplayName("없는 버전은 404")
    void notFound() {
        given(configRepository.findById("rc_none")).willReturn(Optional.empty());

        assertRejected(() -> service.update("rc_none", new UpsertRecommendRequest("x", null, null, null, null)), ErrorCode.RECOMMEND_NOT_FOUND, null);
        assertRejected(() -> service.publish("rc_none"), ErrorCode.RECOMMEND_NOT_FOUND, null);
        assertRejected(() -> service.delete("rc_none"), ErrorCode.RECOMMEND_NOT_FOUND, null);
        verify(configRepository, never()).deactivateAll();
    }

    @Test
    @DisplayName("게시는 나머지를 모두 내린 뒤 이 버전만 active, publishedAt = 지금")
    void publish_onlyOne() {
        CatalogRecommendConfig draft = config("rc_draft", false, null);
        given(configRepository.findById("rc_draft")).willReturn(Optional.of(draft));

        CatalogRecommendResponse res = service.publish("rc_draft");

        verify(configRepository).deactivateAll();
        assertThat(res.active()).isTrue();
        assertThat(res.publishedAt()).isEqualTo(Instant.parse("2026-11-10T08:00:00Z"));
    }

    @Test
    @DisplayName("고치면서 헤드를 바꾸면, 다른 버전이 안 쓰는 옛 헤드는 지우고 쓰는 헤드는 남긴다")
    void update_replacesHead() {
        CatalogImage oldHead = file("oldhead", "application/json");
        CatalogImage newHead = file("newhead", "application/json");
        CatalogRecommendConfig draft = config("rc_draft", false, oldHead);
        given(configRepository.findById("rc_draft")).willReturn(Optional.of(draft));
        given(imageRepository.findById("newhead")).willReturn(Optional.of(newHead));
        given(configRepository.countOtherUses("rc_draft", oldHead)).willReturn(0L);

        CatalogRecommendResponse res = service.update("rc_draft", new UpsertRecommendRequest("둘째", "메모", Map.of(), "newhead", null));

        assertThat(res.head().id()).isEqualTo("newhead");
        assertThat(res.note()).isEqualTo("메모");
        verify(imageRepository).delete(oldHead);
        verify(assetStore).delete("oldhead");

        // 복제한 버전이 같은 헤드를 쓰면 남긴다
        CatalogRecommendConfig copy = config("rc_copy", false, oldHead);
        given(configRepository.findById("rc_copy")).willReturn(Optional.of(copy));
        given(configRepository.countOtherUses("rc_copy", oldHead)).willReturn(1L);
        service.update("rc_copy", new UpsertRecommendRequest("셋째", null, null, "newhead", null));
        verify(assetStore, never()).delete(eq("newhead"));
        verify(imageRepository, org.mockito.Mockito.times(1)).delete(oldHead);
    }

    @Test
    @DisplayName("지우면 아무 버전도 안 쓰게 된 헤드 파일도 지운다")
    void delete_removesUnusedFile() {
        CatalogImage head = file("head", "application/json");
        CatalogRecommendConfig draft = config("rc_draft", false, head);
        given(configRepository.findById("rc_draft")).willReturn(Optional.of(draft));
        given(configRepository.countOtherUses("rc_draft", head)).willReturn(0L);

        service.delete("rc_draft");

        verify(configRepository).delete(draft);
        verify(imageRepository).delete(head);
        verify(assetStore).delete("head");
    }

    // ── 게시본·ETag ──

    @Test
    @DisplayName("게시본이 없으면 비고, 있으면 게시 시각을 updatedAt 으로 싣는다. ETag 는 게시마다 바뀐다")
    void manifest_andEtag() {
        given(configRepository.findFirstByActiveTrueOrderByPublishedAtDesc()).willReturn(Optional.empty());
        Optional<CatalogRecommendManifest> none = service.manifest();
        assertThat(none).isEmpty();
        assertThat(CatalogRecommendService.etag(none)).isEqualTo("W/\"rc-none\"");

        CatalogImage head = file("head", "application/json");
        LocalDateTime t1 = LocalDateTime.of(2026, 11, 10, 8, 0);
        CatalogRecommendConfig live = CatalogRecommendConfig.builder().id("rc_9f2k1x").name("기본")
                .paramsJson("{\"scoring\":{\"listSize\":40}}").head(head).active(true)
                .createdAt(t1.minusHours(1)).updatedAt(t1.minusHours(1)).publishedAt(t1).build();
        given(configRepository.findFirstByActiveTrueOrderByPublishedAtDesc()).willReturn(Optional.of(live));
        Optional<CatalogRecommendManifest> published = service.manifest();
        assertThat(published).isPresent();
        assertThat(published.get().updatedAt()).isEqualTo(Instant.parse("2026-11-10T08:00:00Z"));
        assertThat(published.get().head().url()).isEqualTo("https://cdn.test/catalog/head");
        assertThat(published.get().model()).isNull();
        assertThat(published.get().params()).isEqualTo(Map.of("scoring", Map.of("listSize", 40)));
        String first = CatalogRecommendService.etag(published);
        assertThat(first).isNotEqualTo(CatalogRecommendService.etag(none));

        // 같은 버전을 다시 게시해도(롤백 포함) 게시 시각이 바뀌어 ETag 가 바뀐다
        live.publish(t1.plusMinutes(5));
        assertThat(CatalogRecommendService.etag(service.manifest())).isNotEqualTo(first);
    }

    // ── 파일 ──

    private static String head(int classes, int featureDim, int bucketSize, int bucketValue, int rowLength, int biasSize) {
        StringBuilder sb = new StringBuilder("{\"classes\":[");
        for (int i = 0; i < classes; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(i == classes - 1 ? "기타" : "장소" + i).append('"');
        }
        sb.append("],\"places365_bucket\":[");
        for (int i = 0; i < bucketSize; i++) {
            sb.append(i == 0 ? "" : ",").append(i == 7 ? bucketValue : i % classes);
        }
        sb.append("],\"feature_dim\":").append(featureDim).append(",\"weights\":[");
        for (int r = 0; r < classes; r++) {
            sb.append(r == 0 ? "[" : ",[");
            for (int c = 0; c < rowLength; c++) {
                sb.append(c == 0 ? "" : ",").append("0.01");
            }
            sb.append(']');
        }
        sb.append("],\"bias\":[");
        for (int i = 0; i < biasSize; i++) {
            sb.append(i == 0 ? "" : ",").append("0.5");
        }
        return sb.append("],\"version\":\"v2\"}").toString();
    }

    @Test
    @DisplayName("맞는 헤드 JSON 은 저장하고 id·주소·바이트·kind 를 돌려준다")
    void storeFile_head() {
        byte[] body = head(9, 8, 365, 3, 8 + 9, 9).getBytes(StandardCharsets.UTF_8);

        CatalogRecommendFileResponse res = service.storeFile("head", body, "application/json; charset=utf-8");

        assertThat(res.id()).isEqualTo("file00000000000000000A");
        assertThat(res.kind()).isEqualTo("head");
        assertThat(res.bytes()).isEqualTo(body.length);
        assertThat(res.url()).isEqualTo("https://cdn.test/catalog/file00000000000000000A");
        verify(assetStore).put("file00000000000000000A", "application/json", body);
    }

    @Test
    @DisplayName("헤드 JSON 의 모양이 틀리면 400 RECOMMEND_HEAD_INVALID 에 무엇이 틀렸는지")
    void storeFile_headInvalid() {
        assertRejected(() -> service.storeFile("head", "not json".getBytes(StandardCharsets.UTF_8), "application/json"),
                ErrorCode.RECOMMEND_HEAD_INVALID, "JSON");
        assertRejected(() -> service.storeFile("head", "[1,2]".getBytes(StandardCharsets.UTF_8), "application/json"),
                ErrorCode.RECOMMEND_HEAD_INVALID, "객체");
        assertRejected(() -> service.storeFile("head", head(1, 8, 365, 0, 9, 1).getBytes(StandardCharsets.UTF_8), "application/json"),
                ErrorCode.RECOMMEND_HEAD_INVALID, "classes");
        assertRejected(() -> service.storeFile("head", head(21, 8, 365, 0, 29, 21).getBytes(StandardCharsets.UTF_8), "application/json"),
                ErrorCode.RECOMMEND_HEAD_INVALID, "classes");
        assertRejected(() -> service.storeFile("head", head(9, 8, 364, 3, 17, 9).getBytes(StandardCharsets.UTF_8), "application/json"),
                ErrorCode.RECOMMEND_HEAD_INVALID, "places365_bucket 은 정수 365개");
        assertRejected(() -> service.storeFile("head", head(9, 8, 365, 9, 17, 9).getBytes(StandardCharsets.UTF_8), "application/json"),
                ErrorCode.RECOMMEND_HEAD_INVALID, "places365_bucket[7]");
        assertRejected(() -> service.storeFile("head", head(9, 0, 365, 3, 9, 9).getBytes(StandardCharsets.UTF_8), "application/json"),
                ErrorCode.RECOMMEND_HEAD_INVALID, "feature_dim");
        assertRejected(() -> service.storeFile("head", head(9, 8, 365, 3, 16, 9).getBytes(StandardCharsets.UTF_8), "application/json"),
                ErrorCode.RECOMMEND_HEAD_INVALID, "weights[0]");
        assertRejected(() -> service.storeFile("head", head(9, 8, 365, 3, 17, 8).getBytes(StandardCharsets.UTF_8), "application/json"),
                ErrorCode.RECOMMEND_HEAD_INVALID, "bias");
        assertRejected(() -> service.storeFile("head", "{\"classes\":[\"거리\",\"거리\"]}".getBytes(StandardCharsets.UTF_8), "application/json"),
                ErrorCode.RECOMMEND_HEAD_INVALID, "같은 이름");
        assertRejected(() -> service.storeFile("head", head(9, 8, 365, 3, 17, 9).getBytes(StandardCharsets.UTF_8), "application/octet-stream"),
                ErrorCode.RECOMMEND_HEAD_INVALID, "Content-Type");
        verify(assetStore, never()).put(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("모델은 octet-stream 이고 비어 있지 않아야 하며 상한을 넘으면 413, kind 가 틀리면 400")
    void storeFile_model() {
        CatalogRecommendFileResponse res = service.storeFile("model", new byte[]{8, 1, 2, 3}, "application/octet-stream");
        assertThat(res.kind()).isEqualTo("model");
        assertThat(res.bytes()).isEqualTo(4);
        ArgumentCaptor<CatalogImage> saved = ArgumentCaptor.forClass(CatalogImage.class);
        verify(imageRepository).save(saved.capture());
        assertThat(saved.getValue().getContentType()).isEqualTo("application/octet-stream");

        assertRejected(() -> service.storeFile("model", new byte[0], "application/octet-stream"), ErrorCode.RECOMMEND_MODEL_INVALID, "비어");
        assertRejected(() -> service.storeFile("model", null, "application/octet-stream"), ErrorCode.RECOMMEND_MODEL_INVALID, null);
        assertRejected(() -> service.storeFile("model", new byte[]{1}, "application/json"), ErrorCode.RECOMMEND_MODEL_INVALID, "Content-Type");
        assertRejected(() -> service.storeFile("model", new byte[65], "application/octet-stream"), ErrorCode.RECOMMEND_FILE_TOO_LARGE, null);
        assertRejected(() -> service.storeFile("onnx", new byte[]{1}, "application/octet-stream"), ErrorCode.RECOMMEND_INVALID, "kind");
        assertRejected(() -> service.storeFile(null, new byte[]{1}, "application/octet-stream"), ErrorCode.RECOMMEND_INVALID, "kind");
    }
}
