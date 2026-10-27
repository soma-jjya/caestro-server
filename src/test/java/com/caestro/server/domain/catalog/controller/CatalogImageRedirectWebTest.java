package com.caestro.server.domain.catalog.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.b2b.controller.B2bPublicController;
import com.caestro.server.domain.b2b.service.B2bFillService;
import com.caestro.server.domain.b2b.service.B2bImageService;
import com.caestro.server.domain.b2b.service.B2bProjectService;
import com.caestro.server.domain.catalog.service.CatalogBuiltinService;
import com.caestro.server.domain.catalog.service.CatalogPoseService;
import com.caestro.server.domain.catalog.service.CatalogService;
import com.caestro.server.domain.catalog.store.CatalogAsset;
import com.caestro.server.domain.fill.service.FillWriteGuard;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.config.WebMvcConfig;
import com.caestro.server.global.jwt.JwtProvider;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 그림 GET 이 저장소에 따라 302(CDN, 30일 캐시) 또는 200(바이트 중계)으로 갈리는지 (#186). 카탈로그와 B2B 둘 다 인증 없이 열린다. */
@WebMvcTest(controllers = {CatalogController.class, B2bPublicController.class}, properties = "catalog.studio-key=test-studio-key")
@Import({SecurityConfig.class, WebMvcConfig.class})
class CatalogImageRedirectWebTest {

    private static final URI CDN = URI.create("https://cdn.test/catalog/img1");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CatalogService catalogService;
    @MockitoBean private CatalogPoseService poseService;
    @MockitoBean private CatalogBuiltinService builtinService;
    @MockitoBean private B2bProjectService projects;
    @MockitoBean private B2bFillService fills;
    @MockitoBean private B2bImageService images;
    @MockitoBean private FillWriteGuard writeGuard;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private RedisTemplate<String, String> redisTemplate;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("저장소가 CDN 주소를 주면 302 + Location, 바이트는 읽지 않고 302 자체를 30일 캐시한다")
    void catalog_redirectsToCdn() throws Exception {
        given(catalogService.imageRedirect("img1")).willReturn(Optional.of(CDN));

        mockMvc.perform(get("/catalog/images/img1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", CDN.toString()))
                .andExpect(header().string("Cache-Control", "max-age=2592000, public"));

        verify(catalogService, never()).image(anyString());
    }

    @Test
    @DisplayName("[회귀] 주소가 없으면(RDS) 전처럼 바이트를 200 으로 중계하고 30일 캐시한다")
    void catalog_relaysBytesWithoutRedirect() throws Exception {
        given(catalogService.imageRedirect("img1")).willReturn(Optional.empty());
        given(catalogService.image("img1")).willReturn(new CatalogAsset("img1", "image/png", new byte[]{1, 2, 3}));

        mockMvc.perform(get("/catalog/images/img1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "max-age=2592000, public"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("B2B 사본 주소도 같은 규칙으로 302")
    void b2b_redirectsToCdn() throws Exception {
        given(images.imageRedirect("bi_1")).willReturn(Optional.of(URI.create("https://cdn.test/catalog/bi_1")));

        mockMvc.perform(get("/b2b/images/bi_1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://cdn.test/catalog/bi_1"));

        verify(images, never()).image(anyString());
    }
}
