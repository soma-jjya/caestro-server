package com.caestro.server.domain.push.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.push.dto.response.PushTestResponse;
import com.caestro.server.domain.push.dto.response.PushTokenResponse;
import com.caestro.server.domain.push.enums.PushPlatform;
import com.caestro.server.domain.push.service.PushService;
import com.caestro.server.domain.push.service.PushTokenService;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.jwt.JwtProvider;
import com.caestro.server.global.security.CustomUserDetails;
import java.time.LocalDateTime;
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

/** 기기 토큰 API(#206)는 JWT 가 있어야 열리고(게스트 포함), 검증 실패는 400. */
@WebMvcTest(controllers = PushTokenController.class)
@Import(SecurityConfig.class)
class PushTokenControllerWebTest {

    private static final CustomUserDetails ME = new CustomUserDetails(7L, User.Role.USER);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PushTokenService pushTokenService;
    @MockitoBean private PushService pushService;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private RedisTemplate<String, String> redisTemplate;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("등록: 로그인한 사용자가 platform·token 을 보내면 200, 누락이면 400, 토큰 없이는 열리지 않는다")
    void register() throws Exception {
        given(pushTokenService.register(eq(7L), any()))
                .willReturn(new PushTokenResponse(1L, PushPlatform.ANDROID, LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(put("/users/me/push-token").with(user(ME)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"ANDROID\",\"token\":\"tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("ANDROID"));

        mockMvc.perform(put("/users/me/push-token").with(user(ME)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"ANDROID\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/users/me/push-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"ANDROID\",\"token\":\"tok\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("해제는 쿼리의 token 으로 204, 테스트 발송은 tokens·sent 를 돌려준다")
    void unregisterAndTest() throws Exception {
        mockMvc.perform(delete("/users/me/push-token").with(user(ME)).param("token", "tok"))
                .andExpect(status().isNoContent());
        verify(pushTokenService).unregister(7L, "tok");

        given(pushService.sendTest(7L)).willReturn(new PushTestResponse(2, 1));
        mockMvc.perform(post("/users/me/push-token/test").with(user(ME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens").value(2))
                .andExpect(jsonPath("$.sent").value(1));
    }
}
