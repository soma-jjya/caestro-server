package com.caestro.server.domain.catalog.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.catalog.dto.response.CatalogRecommendFileResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendManifest;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendResponse;
import com.caestro.server.domain.catalog.dto.response.RecommendFileDto;
import com.caestro.server.domain.catalog.service.CatalogBuiltinService;
import com.caestro.server.domain.catalog.service.CatalogPoseService;
import com.caestro.server.domain.catalog.service.CatalogRecommendService;
import com.caestro.server.domain.catalog.service.CatalogService;
import com.caestro.server.domain.catalog.store.CatalogAsset;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.config.WebMvcConfig;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.jwt.JwtProvider;
import com.caestro.server.global.security.CustomUserDetails;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 추천 설정(contracts/recommend-config.md) 웹 계층: 게시본은 인증 없이 {}·게시본 모양·ETag/304, 스튜디오 경로는 키(또는 ADMIN)만 —
 * 앱 사용자 토큰은 403, 파일 올리기는 kind 별 상한을 본문 전에 거른다, 헤드 JSON 은 /catalog/images 로 그대로 나간다.
 */
@WebMvcTest(controllers = {CatalogRecommendController.class, CatalogController.class})
@Import({SecurityConfig.class, WebMvcConfig.class})
@TestPropertySource(properties = {"catalog.studio-key=test-studio-key",
        "catalog.recommend.max-head-bytes=1024", "catalog.recommend.max-model-bytes=2048"})
class CatalogRecommendWebTest {

    static final String KEY = "X-Studio-Key";
    static final String STUDIO_KEY = "test-studio-key";
    static final RecommendFileDto HEAD = new RecommendFileDto("ci_xxx", "https://api.peakpic.app/catalog/images/ci_xxx", 81234);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CatalogRecommendService recommendService;
    @MockitoBean private CatalogService catalogService;
    @MockitoBean private CatalogPoseService poseService;
    @MockitoBean private CatalogBuiltinService builtinService;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private RedisTemplate<String, String> redisTemplate;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private static CatalogRecommendManifest manifest(String id, Instant publishedAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("place", Map.of("sure", 0.55));
        params.put("scoring", Map.of("listSize", 40));
        params.put("placeToPool", Map.of("거리", List.of("거리", "계단")));
        return new CatalogRecommendManifest(id, "기본 + 거리→계단", publishedAt, params, HEAD, null);
    }

    private static CatalogRecommendResponse item(String id, boolean active) {
        Instant t = Instant.parse("2026-11-10T08:00:00Z");
        return new CatalogRecommendResponse(id, "기본", null, Map.of(), null, null, active, t, t, active ? t : null);
    }

    @Test
    @DisplayName("게시본이 없으면 인증 없이 200 {} 와 ETag, 같은 ETag 면 304")
    void manifest_empty() throws Exception {
        given(recommendService.manifest()).willReturn(Optional.empty());

        String etag = mockMvc.perform(get("/catalog/recommend"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}", true))
                .andExpect(header().string("ETag", "W/\"rc-none\""))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn().getResponse().getHeader("ETag");
        mockMvc.perform(get("/catalog/recommend").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("게시본 모양: id·name·updatedAt·params·head{id,url,bytes}·model null. 게시가 바뀌면 ETag 도 바뀌어 옛 ETag 로는 200")
    void manifest_published_etagChangesOnPublish() throws Exception {
        given(recommendService.manifest()).willReturn(Optional.of(manifest("rc_9f2k1x", Instant.parse("2026-11-10T08:00:00Z"))));

        String first = mockMvc.perform(get("/catalog/recommend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("rc_9f2k1x"))
                .andExpect(jsonPath("$.name").value("기본 + 거리→계단"))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-10T08:00:00Z"))
                .andExpect(jsonPath("$.params.place.sure").value(0.55))
                .andExpect(jsonPath("$.params.scoring.listSize").value(40))
                .andExpect(jsonPath("$.params.placeToPool['거리'][1]").value("계단"))
                .andExpect(jsonPath("$.head.id").value("ci_xxx"))
                .andExpect(jsonPath("$.head.url").value("https://api.peakpic.app/catalog/images/ci_xxx"))
                .andExpect(jsonPath("$.head.bytes").value(81234))
                .andExpect(jsonPath("$.model").value(nullValue()))
                .andReturn().getResponse().getHeader("ETag");
        mockMvc.perform(get("/catalog/recommend").header("If-None-Match", first)).andExpect(status().isNotModified());

        // 다른 버전을 게시(또는 같은 버전을 다시 게시)하면 게시 시각이 바뀐다
        given(recommendService.manifest()).willReturn(Optional.of(manifest("rc_other0001", Instant.parse("2026-11-10T09:00:00Z"))));
        String second = mockMvc.perform(get("/catalog/recommend").header("If-None-Match", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("rc_other0001"))
                .andReturn().getResponse().getHeader("ETag");
        org.assertj.core.api.Assertions.assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("스튜디오 경로는 키 없음·틀린 키·앱 사용자 토큰이면 403, 맞는 키와 ADMIN 은 통과")
    void studio_access() throws Exception {
        given(recommendService.list()).willReturn(new CatalogRecommendListResponse(null, List.of()));

        mockMvc.perform(get("/catalog/studio/recommend")).andExpect(status().isForbidden());
        mockMvc.perform(get("/catalog/studio/recommend").header(KEY, "wrong")).andExpect(status().isForbidden());
        mockMvc.perform(get("/catalog/studio/recommend").with(user(new CustomUserDetails(1L, User.Role.USER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/catalog/studio/recommend").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/catalog/studio/recommend/unpublish")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/catalog/studio/recommend/rc_x")).andExpect(status().isForbidden());
        mockMvc.perform(post("/catalog/studio/recommend/files").param("kind", "head")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(recommendService);

        mockMvc.perform(get("/catalog/studio/recommend").header(KEY, STUDIO_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeId").value(nullValue()))
                .andExpect(jsonPath("$.items").isArray());
        mockMvc.perform(get("/catalog/studio/recommend").with(user(new CustomUserDetails(2L, User.Role.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("만들기 201, 게시 200, 게시 내리기·지우기 204, 게시 중 고치기 409 는 message 와 함께")
    void studio_writes() throws Exception {
        given(recommendService.create(any())).willReturn(item("rc_new0000001", false));
        given(recommendService.publish("rc_new0000001")).willReturn(item("rc_new0000001", true));
        given(recommendService.update(eq("rc_live000001"), any())).willThrow(new CustomException(ErrorCode.RECOMMEND_ACTIVE_LOCKED));
        given(recommendService.create(org.mockito.ArgumentMatchers.argThat(r -> r != null && "bad".equals(r.name()))))
                .willThrow(new CustomException(ErrorCode.RECOMMEND_INVALID, "params.place 에 모르는 키가 있습니다: sur"));

        mockMvc.perform(post("/catalog/studio/recommend").header(KEY, STUDIO_KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"기본\",\"params\":{\"place\":{\"sure\":0.6}},\"headId\":null,\"modelId\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("rc_new0000001"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.publishedAt").value(nullValue()));
        mockMvc.perform(post("/catalog/studio/recommend").header(KEY, STUDIO_KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"params\":{\"place\":{\"sur\":0.6}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("추천 설정 값이 올바르지 않습니다: params.place 에 모르는 키가 있습니다: sur"));
        mockMvc.perform(post("/catalog/studio/recommend/rc_new0000001/publish").header(KEY, STUDIO_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(put("/catalog/studio/recommend/rc_live000001").header(KEY, STUDIO_KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"고침\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.RECOMMEND_ACTIVE_LOCKED.getMessage()));
        mockMvc.perform(post("/catalog/studio/recommend/unpublish").header(KEY, STUDIO_KEY)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/catalog/studio/recommend/rc_old0000001").header(KEY, STUDIO_KEY)).andExpect(status().isNoContent());

        verify(recommendService).unpublish();
        verify(recommendService).delete("rc_old0000001");
    }

    @Test
    @DisplayName("파일 올리기: 헤드 상한(1KB)·모델 상한(2KB)을 본문 전에 413 으로, 상한 안이면 201")
    void upload_sizeGuardPerKind() throws Exception {
        given(recommendService.storeFile(eq("model"), any(byte[].class), anyString()))
                .willReturn(new CatalogRecommendFileResponse("m1", "https://api.peakpic.app/catalog/images/m1", 1500, "model"));

        mockMvc.perform(post("/catalog/studio/recommend/files").param("kind", "head").header(KEY, STUDIO_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(new byte[1025]))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.message").value(ErrorCode.RECOMMEND_FILE_TOO_LARGE.getMessage()));
        mockMvc.perform(post("/catalog/studio/recommend/files").param("kind", "model").header(KEY, STUDIO_KEY)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[2049]))
                .andExpect(status().is(413));
        mockMvc.perform(post("/catalog/studio/recommend/files").param("kind", "head").header(KEY, STUDIO_KEY)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(411));
        verifyNoInteractions(recommendService);

        // 모델은 헤드 상한보다 커도 자기 상한 안이면 통과
        mockMvc.perform(post("/catalog/studio/recommend/files").param("kind", "model").header(KEY, STUDIO_KEY)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[1500]))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("m1"))
                .andExpect(jsonPath("$.bytes").value(1500))
                .andExpect(jsonPath("$.kind").value("model"));
    }

    @Test
    @DisplayName("헤드 검증 실패는 400 과 무엇이 틀렸는지")
    void upload_headInvalid() throws Exception {
        given(recommendService.storeFile(eq("head"), any(byte[].class), anyString()))
                .willThrow(new CustomException(ErrorCode.RECOMMEND_HEAD_INVALID, "places365_bucket 은 정수 365개 배열이어야 합니다 (지금 364개)"));

        mockMvc.perform(post("/catalog/studio/recommend/files").param("kind", "head").header(KEY, STUDIO_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"classes\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("헤드 JSON 이 올바르지 않습니다: places365_bucket 은 정수 365개 배열이어야 합니다 (지금 364개)"));
    }

    @Test
    @DisplayName("RDS 저장소에서 헤드 JSON 은 /catalog/images 로 원래 바이트 그대로(application/json) 나간다")
    void headFile_servedRaw() throws Exception {
        byte[] json = "{\"classes\":[\"거리\",\"기타\"]}".getBytes(StandardCharsets.UTF_8);
        given(catalogService.imageRedirect("head1")).willReturn(Optional.empty());
        given(catalogService.image("head1")).willReturn(new CatalogAsset("head1", "application/json", json));

        mockMvc.perform(get("/catalog/images/head1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().bytes(json));
    }
}
