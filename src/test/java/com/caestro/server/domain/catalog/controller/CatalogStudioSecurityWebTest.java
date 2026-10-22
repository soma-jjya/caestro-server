package com.caestro.server.domain.catalog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.catalog.dto.response.CatalogFrameListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogImageResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogVideoResponse;
import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.domain.catalog.service.CatalogPoseService;
import com.caestro.server.domain.catalog.service.CatalogService;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.config.WebMvcConfig;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.jwt.JwtProvider;
import java.time.Instant;
import java.util.List;
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
 * 스튜디오 경로는 X-Studio-Key 가 맞을 때만 열리고, 읽기는 인증 없이 열리며, 그림 올리기는 상한을 본문 전에 거른다.
 * ?all=true 는 키가 있을 때만 숨김을 포함한다. ETag 가 같으면 304.
 */
@WebMvcTest(controllers = {CatalogController.class, CatalogStudioController.class})
@Import({SecurityConfig.class, WebMvcConfig.class})
@TestPropertySource(properties = {"catalog.studio-key=test-studio-key", "catalog.max-image-bytes=1024", "catalog.max-video-bytes=2048"})
class CatalogStudioSecurityWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CatalogService catalogService;
    @MockitoBean private CatalogPoseService poseService;
    @MockitoBean private com.caestro.server.domain.catalog.service.CatalogBuiltinService builtinService;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private RedisTemplate<String, String> redisTemplate;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("프레임 목록은 인증 없이 열린다")
    void frames_public() throws Exception {
        given(catalogService.frames(false)).willReturn(new CatalogFrameListResponse(null, List.of()));

        mockMvc.perform(get("/catalog/frames"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.frames").isArray());
    }

    @Test
    @DisplayName("ETag 가 같으면 304")
    void frames_notModified() throws Exception {
        Instant t = Instant.parse("2026-10-22T06:00:00Z");
        given(catalogService.frames(false)).willReturn(new CatalogFrameListResponse(t, List.of()));

        String etag = mockMvc.perform(get("/catalog/frames")).andReturn().getResponse().getHeader("ETag");
        mockMvc.perform(get("/catalog/frames").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("?all=true 는 키 없이는 숨김을 포함하지 않고, 키가 있으면 포함한다")
    void frames_allNeedsKey() throws Exception {
        given(catalogService.frames(anyBoolean())).willReturn(new CatalogFrameListResponse(null, List.of()));

        mockMvc.perform(get("/catalog/frames").param("all", "true")).andExpect(status().isOk());
        verify(catalogService).frames(false);

        mockMvc.perform(get("/catalog/frames").param("all", "true").header("X-Studio-Key", "test-studio-key"))
                .andExpect(status().isOk());
        verify(catalogService).frames(true);
    }

    @Test
    @DisplayName("포즈추천 덧씌우기는 인증 없이 열리고, 키가 있을 때만 all=true 가 숨김을 포함한다")
    void pose_public() throws Exception {
        given(poseService.pose(anyBoolean())).willReturn(new CatalogPoseResponse(null, List.of(), List.of()));

        mockMvc.perform(get("/catalog/pose").param("all", "true"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.overrides").isArray())
                .andExpect(jsonPath("$.uploads").isArray());
        verify(poseService).pose(false);

        mockMvc.perform(get("/catalog/pose").param("all", "true").header("X-Studio-Key", "test-studio-key"))
                .andExpect(status().isOk());
        verify(poseService).pose(true);
    }

    @Test
    @DisplayName("휴지통·복구·포즈 쓰기도 키 없이는 403")
    void trashAndPose_forbiddenWithoutKey() throws Exception {
        mockMvc.perform(get("/catalog/studio/trash")).andExpect(status().isForbidden());
        mockMvc.perform(post("/catalog/studio/frames/af_x/restore")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/catalog/studio/trash/banners/hb_x")).andExpect(status().isForbidden());
        mockMvc.perform(post("/catalog/studio/pose/uploads").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(catalogService, poseService);
    }

    @Test
    @DisplayName("맞는 키면 휴지통 목록 200, 완전 삭제 204")
    void trash_allowedWithKey() throws Exception {
        given(catalogService.trashedFrames()).willReturn(List.of());
        given(catalogService.trashedBanners()).willReturn(List.of());
        given(poseService.trashedUploads()).willReturn(List.of());
        given(poseService.trashedOverrides()).willReturn(List.of());

        mockMvc.perform(get("/catalog/studio/trash").header("X-Studio-Key", "test-studio-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frames").isArray())
                .andExpect(jsonPath("$.poseOverrides").isArray());
        mockMvc.perform(delete("/catalog/studio/trash/pose/uploads/pu_x").header("X-Studio-Key", "test-studio-key"))
                .andExpect(status().isNoContent());
        verify(poseService).purgeUpload(eq("pu_x"));
    }

    @Test
    @DisplayName("스튜디오 쓰기는 키 없이 403, 틀린 키도 403")
    void studio_forbiddenWithoutKey() throws Exception {
        mockMvc.perform(post("/catalog/studio/frames").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/catalog/studio/frames").header("X-Studio-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/catalog/studio/banners/hb_x"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(catalogService, poseService);
    }

    @Test
    @DisplayName("맞는 키면 그림 올리기 201, 프레임 삭제 204")
    void studio_allowedWithKey() throws Exception {
        given(catalogService.storeImage(any(byte[].class), anyString()))
                .willReturn(new CatalogImageResponse("img", "https://api.peakpic.app/catalog/images/img", 10, 10, "image/png"));

        mockMvc.perform(post("/catalog/studio/images").header("X-Studio-Key", "test-studio-key")
                        .contentType(MediaType.IMAGE_PNG).content(new byte[100]))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageId").value("img"));

        mockMvc.perform(delete("/catalog/studio/frames/af_x").header("X-Studio-Key", "test-studio-key"))
                .andExpect(status().isNoContent());
        verify(catalogService).deleteFrame(eq("af_x"));
    }

    @Test
    @DisplayName("그림 상한을 넘는 Content-Length 는 서비스에 닿기 전에 413")
    void upload_tooLargeBeforeBody() throws Exception {
        mockMvc.perform(post("/catalog/studio/images").header("X-Studio-Key", "test-studio-key")
                        .contentType(MediaType.IMAGE_PNG).content(new byte[1025]))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.message").value(ErrorCode.CATALOG_IMAGE_TOO_LARGE.getMessage()));

        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("동영상 올리기: 키 없이 403, 맞는 키면 201, 상한을 넘는 Content-Length 는 서비스 전에 413")
    void uploadVideo() throws Exception {
        MediaType mp4 = MediaType.parseMediaType("video/mp4");
        mockMvc.perform(post("/catalog/studio/videos").param("width", "10").param("height", "10").param("durationMs", "1000")
                        .contentType(mp4).content(new byte[100]))
                .andExpect(status().isForbidden());

        given(catalogService.storeVideo(any(byte[].class), anyString(), eq(1080), eq(1350), eq(8200)))
                .willReturn(new CatalogVideoResponse("vid", "https://api.peakpic.app/catalog/images/vid", 1080, 1350, 8200, "video/mp4"));
        mockMvc.perform(post("/catalog/studio/videos").header("X-Studio-Key", "test-studio-key")
                        .param("width", "1080").param("height", "1350").param("durationMs", "8200")
                        .contentType(mp4).content(new byte[100]))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.videoId").value("vid"))
                .andExpect(jsonPath("$.durationMs").value(8200));

        mockMvc.perform(post("/catalog/studio/videos").header("X-Studio-Key", "test-studio-key")
                        .param("width", "1080").param("height", "1350").param("durationMs", "8200")
                        .contentType(mp4).content(new byte[2049]))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.message").value(ErrorCode.CATALOG_VIDEO_TOO_LARGE.getMessage()));
        verify(catalogService, org.mockito.Mockito.times(1)).storeVideo(any(byte[].class), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("동영상 바이트는 Range 요청에 206 과 Content-Range 로 답하고, 그림은 그대로 200")
    void video_rangeRequest() throws Exception {
        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        given(catalogService.image("vid")).willReturn(new StoredImage("vid", "video/mp4", data));
        given(catalogService.image("img")).willReturn(new StoredImage("img", "image/png", new byte[]{1, 2, 3}));

        mockMvc.perform(get("/catalog/images/vid").header("Range", "bytes=10-19"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Range", "bytes 10-19/100"))
                .andExpect(content().bytes(java.util.Arrays.copyOfRange(data, 10, 20)));

        mockMvc.perform(get("/catalog/images/vid"))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(content().contentType("video/mp4"))
                .andExpect(content().bytes(data));

        mockMvc.perform(get("/catalog/images/img"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }
}
