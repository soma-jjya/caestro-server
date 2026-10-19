package com.caestro.server.domain.fill.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.domain.fill.service.FillWriteGuard;
import com.caestro.server.domain.fill.store.StoredImage;
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

/** 이미지 GET이 저장소에 따라 302(S3 서명 주소) 또는 200(바이트 중계)으로 갈리는지 (#152). 둘 다 인증 없이 열린다. */
@WebMvcTest(controllers = FillController.class)
@Import({SecurityConfig.class, WebMvcConfig.class})
class FillImageRedirectWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private FillService fillService;
    @MockitoBean private FillWriteGuard writeGuard;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private RedisTemplate<String, String> redisTemplate;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("저장소가 주소를 주면 302 + Location, 바이트는 읽지 않고 캐시도 금지한다")
    void redirect_whenStoreProvidesUrl() throws Exception {
        given(fillService.imageRedirect("K7X2MQ", "x"))
                .willReturn(Optional.of(URI.create("https://bucket.s3.amazonaws.com/fills/1/x?X-Amz-Signature=s")));

        mockMvc.perform(get("/fills/K7X2MQ/images/x"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://bucket.s3.amazonaws.com/fills/1/x?X-Amz-Signature=s"))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(fillService, never()).image(anyString(), anyString());
    }

    @Test
    @DisplayName("[회귀] 주소가 없으면 전처럼 바이트를 200으로 중계하고 7일 캐시한다")
    void bytes_whenNoRedirect() throws Exception {
        given(fillService.imageRedirect("K7X2MQ", "x")).willReturn(Optional.empty());
        given(fillService.image("K7X2MQ", "x")).willReturn(new StoredImage("x", "image/jpeg", new byte[]{1, 2, 3}));

        mockMvc.perform(get("/fills/K7X2MQ/images/x"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=604800, public"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }
}
