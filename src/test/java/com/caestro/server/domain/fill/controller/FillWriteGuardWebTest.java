package com.caestro.server.domain.fill.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.domain.fill.service.FillWriteGuard;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.config.WebMvcConfig;
import com.caestro.server.global.exception.CustomException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 컨트롤러가 쓰기 4종에서 서비스보다 먼저 가드를 부르고, 가드의 429가 그대로 응답되는지 (#150). */
@WebMvcTest(controllers = FillController.class)
@Import({SecurityConfig.class, WebMvcConfig.class})
class FillWriteGuardWebTest {

    private static final CustomUserDetails OWNER = new CustomUserDetails(1L, User.Role.USER);
    private static final String CREATE_BODY = "{\"frame\":\"Black\",\"layout\":\"Strip\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FillService fillService;

    @MockitoBean
    private FillWriteGuard writeGuard;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private RedisTemplate<String, String> redisTemplate;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("세션 생성은 checkCreate를 거친다")
    void create_callsGuard() throws Exception {
        mockMvc.perform(post("/fills").with(user(OWNER)).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());

        verify(writeGuard).checkCreate(1L);
    }

    @Test
    @DisplayName("생성 한도 초과면 429가 응답되고 서비스는 호출되지 않는다")
    void create_rejected_serviceUntouched() throws Exception {
        doThrow(new CustomException(ErrorCode.FILL_TOO_MANY_SESSIONS)).when(writeGuard).checkCreate(anyLong());

        mockMvc.perform(post("/fills").with(user(OWNER)).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.message").value(ErrorCode.FILL_TOO_MANY_SESSIONS.getMessage()));

        verifyNoInteractions(fillService);
    }

    @Test
    @DisplayName("업로드 3종(칸 사진·프레임 요소·GIF)은 모두 checkUpload를 거친다")
    void uploads_callGuard() throws Exception {
        mockMvc.perform(put("/fills/K7X2MQ/slots/0").with(user(OWNER)).contentType(MediaType.IMAGE_JPEG).content(new byte[16]))
                .andExpect(status().isOk());
        mockMvc.perform(put("/fills/K7X2MQ/frame-assets/0").with(user(OWNER)).contentType(MediaType.IMAGE_PNG).content(new byte[16]))
                .andExpect(status().isOk());
        mockMvc.perform(put("/fills/K7X2MQ/gif").with(user(OWNER)).contentType(MediaType.IMAGE_GIF).content(new byte[16]))
                .andExpect(status().isOk());

        verify(writeGuard, org.mockito.Mockito.times(3)).checkUpload(1L);
    }

    @Test
    @DisplayName("업로드 한도 초과면 429, 서비스는 호출되지 않는다")
    void upload_rejected_serviceUntouched() throws Exception {
        doThrow(new CustomException(ErrorCode.TOO_MANY_REQUESTS)).when(writeGuard).checkUpload(anyLong());

        mockMvc.perform(put("/fills/K7X2MQ/gif").with(user(OWNER)).contentType(MediaType.IMAGE_GIF).content(new byte[16]))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.message").value(ErrorCode.TOO_MANY_REQUESTS.getMessage()));

        verifyNoInteractions(fillService);
    }

    @Test
    @DisplayName("[회귀] 비우기·삭제는 저장소를 늘리지 않으므로 가드를 거치지 않는다")
    void deletes_bypassGuard() throws Exception {
        mockMvc.perform(delete("/fills/K7X2MQ/slots/0").with(user(OWNER))).andExpect(status().isOk());
        mockMvc.perform(delete("/fills/K7X2MQ").with(user(OWNER))).andExpect(status().isNoContent());

        verifyNoInteractions(writeGuard);
        verify(fillService).clearSlot(any(), any(Integer.class), anyLong());
        verify(fillService).delete(any(), anyLong());
    }
}
