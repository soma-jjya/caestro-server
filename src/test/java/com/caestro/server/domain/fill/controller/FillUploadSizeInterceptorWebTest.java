package com.caestro.server.domain.fill.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.domain.fill.service.FillWriteGuard;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.config.WebMvcConfig;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.jwt.JwtProvider;
import com.caestro.server.global.security.CustomUserDetails;
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
 * 업로드 본문이 힙에 올라가기 전에 Content-Length만 보고 거절하는지 검증한다 (#149).
 * 상한을 작게 잡아(사진 1KB, GIF 2KB) 경로별 한도가 따로 적용되는 것까지 본다.
 */
@WebMvcTest(controllers = FillController.class)
@Import({SecurityConfig.class, WebMvcConfig.class})
@TestPropertySource(properties = {"fill.max-image-bytes=1024", "fill.max-gif-bytes=2048", "fill.max-clip-bytes=1536"})
class FillUploadSizeInterceptorWebTest {

    private static final CustomUserDetails OWNER = new CustomUserDetails(1L, User.Role.USER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FillService fillService;

    // 컨트롤러 의존성(#150). 목이라 아무것도 거절하지 않는다 — 크기 검사만 본다
    @MockitoBean
    private FillWriteGuard writeGuard;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private RedisTemplate<String, String> redisTemplate;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스에서 요구하는 빈
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("칸 사진 상한을 넘는 Content-Length는 서비스에 닿기 전에 413")
    void 칸_사진_상한_초과는_서비스_전_413() throws Exception {
        mockMvc.perform(put("/fills/K7X2MQ/slots/0").with(user(OWNER))
                        .contentType(MediaType.IMAGE_JPEG).content(new byte[1025]))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.message").value(ErrorCode.FILL_IMAGE_TOO_LARGE.getMessage()));

        verifyNoInteractions(fillService);
    }

    @Test
    @DisplayName("프레임 요소도 같은 사진 상한을 적용한다")
    void 프레임_요소_상한_초과는_413() throws Exception {
        mockMvc.perform(put("/fills/K7X2MQ/frame-assets/0").with(user(OWNER))
                        .contentType(MediaType.IMAGE_PNG).content(new byte[1025]))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.message").value(ErrorCode.FILL_IMAGE_TOO_LARGE.getMessage()));

        verifyNoInteractions(fillService);
    }

    @Test
    @DisplayName("GIF는 자기 상한(더 큼)을 쓴다: 사진 상한은 넘어도 통과, GIF 상한은 넘으면 413")
    void GIF는_별도_상한() throws Exception {
        mockMvc.perform(put("/fills/K7X2MQ/gif").with(user(OWNER))
                        .contentType(MediaType.IMAGE_GIF).content(new byte[1500]))
                .andExpect(status().isOk());
        verify(fillService).uploadGif(anyString(), anyLong(), any(byte[].class), anyString());

        mockMvc.perform(put("/fills/K7X2MQ/gif").with(user(OWNER))
                        .contentType(MediaType.IMAGE_GIF).content(new byte[2049]))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.message").value(ErrorCode.FILL_GIF_TOO_LARGE.getMessage()));
    }

    @Test
    @DisplayName("칸 클립은 클립 상한을 쓴다: 사진 상한은 넘어도 통과, 클립 상한을 넘으면 GIF 초과로 413")
    void 칸_클립은_클립_상한() throws Exception {
        mockMvc.perform(put("/fills/K7X2MQ/slots/1/clip").with(user(OWNER))
                        .contentType(MediaType.IMAGE_GIF).content(new byte[1500]))
                .andExpect(status().isOk());
        verify(fillService).uploadSlotClip(anyString(), anyInt(), anyLong(), any(byte[].class), anyString());

        mockMvc.perform(put("/fills/K7X2MQ/slots/1/clip").with(user(OWNER))
                        .contentType(MediaType.IMAGE_GIF).content(new byte[1537]))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.message").value(ErrorCode.FILL_GIF_TOO_LARGE.getMessage()));
    }

    @Test
    @DisplayName("Content-Length 없는(chunked) 업로드는 본문을 읽지 않고 411")
    void 길이_없는_업로드는_411() throws Exception {
        mockMvc.perform(put("/fills/K7X2MQ/slots/0").with(user(OWNER))
                        .contentType(MediaType.IMAGE_JPEG))
                .andExpect(status().is(411))
                .andExpect(jsonPath("$.message").value(ErrorCode.LENGTH_REQUIRED.getMessage()));

        verifyNoInteractions(fillService);
    }

    @Test
    @DisplayName("[회귀] 상한 이내 업로드는 그대로 서비스에 전달된다")
    void 상한_이내는_통과() throws Exception {
        mockMvc.perform(put("/fills/K7X2MQ/slots/2").with(user(OWNER))
                        .contentType(MediaType.IMAGE_JPEG).content(new byte[1024]))
                .andExpect(status().isOk());

        verify(fillService).fillSlot(anyString(), anyInt(), anyLong(), isNull(), any(byte[].class), anyString());
    }

    @Test
    @DisplayName("[회귀] 같은 경로의 DELETE(칸 비우기)는 본문이 없어도 통과한다")
    void 칸_비우기_DELETE는_검사_대상_아님() throws Exception {
        mockMvc.perform(delete("/fills/K7X2MQ/slots/0").with(user(OWNER)))
                .andExpect(status().isOk());

        verify(fillService).clearSlot(anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("[회귀] 업로드가 아닌 /fills 경로(JSON)는 검사하지 않는다")
    void JSON_경로는_검사_대상_아님() throws Exception {
        mockMvc.perform(put("/fills/K7X2MQ/frame").with(user(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frame\":\"Black\",\"layout\":\"Strip\",\"title\":\"" + "x".repeat(1100) + "\"}"))
                .andExpect(status().isOk());

        verify(fillService).updateFrame(anyString(), anyLong(), any());
    }
}
