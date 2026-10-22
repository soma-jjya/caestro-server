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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.catalog.dto.response.CatalogFrameListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogImageResponse;
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
@TestPropertySource(properties = {"catalog.studio-key=test-studio-key", "catalog.max-image-bytes=1024"})
class CatalogStudioSecurityWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CatalogService catalogService;
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
    @DisplayName("스튜디오 쓰기는 키 없이 403, 틀린 키도 403")
    void studio_forbiddenWithoutKey() throws Exception {
        mockMvc.perform(post("/catalog/studio/frames").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/catalog/studio/frames").header("X-Studio-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/catalog/studio/banners/hb_x"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(catalogService);
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
}
