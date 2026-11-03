package com.caestro.server.domain.ops.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.ops.dto.response.OpsStatsResponse;
import com.caestro.server.domain.ops.dto.response.OpsSubscriptionPageResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserPageResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserResponse;
import com.caestro.server.domain.ops.service.StudioOpsService;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.jwt.JwtProvider;
import com.caestro.server.global.security.CustomUserDetails;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 운영 조회(contracts/studio-ops.md): 스튜디오 키 또는 ADMIN 만, 일반 사용자 토큰은 403. 쿼리는 그대로 서비스로. */
@WebMvcTest(controllers = StudioOpsController.class, properties = "catalog.studio-key=test-server-only-key")
@Import(SecurityConfig.class)
class StudioOpsWebTest {

    static final String KEY = "X-Studio-Key";

    @Autowired MockMvc mockMvc;
    @MockitoBean StudioOpsService studioOpsService;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean RedisTemplate<String, String> redisTemplate;
    @MockitoBean JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("키 없음·틀린 키·일반 사용자는 403, 스튜디오 키와 ADMIN 은 200")
    void access() throws Exception {
        given(studioOpsService.stats()).willReturn(new OpsStatsResponse(
                new OpsStatsResponse.Users(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                new OpsStatsResponse.Subscriptions(0, 0, 0, 0, 0), LocalDateTime.now()));
        mockMvc.perform(get("/admin/ops/stats")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/ops/stats").header(KEY, "wrong")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/ops/stats").with(user(new CustomUserDetails(1L, User.Role.USER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/ops/stats").header(KEY, "test-server-only-key")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/ops/stats").with(user(new CustomUserDetails(2L, User.Role.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("스튜디오 키는 /admin/ops 밖의 /admin 에는 통하지 않는다")
    void keyStaysInOps() throws Exception {
        mockMvc.perform(get("/admin/other").header(KEY, "test-server-only-key")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("사용자 목록의 검색·필터·커서, 없는 사용자 404, 구독 목록 필터")
    void endpoints() throws Exception {
        OpsUserResponse row = new OpsUserResponse(12L, "google", "ANDROID", "이상협", LocalDateTime.now(), null, null);
        given(studioOpsService.users("상협", "google", true, 40L, 10)).willReturn(new OpsUserPageResponse(List.of(row), 12L));
        given(studioOpsService.user(99L)).willThrow(new CustomException(ErrorCode.USER_NOT_FOUND));
        given(studioOpsService.subscriptions(any(), any(), any(), any())).willReturn(new OpsSubscriptionPageResponse(List.of(), null));

        mockMvc.perform(get("/admin/ops/users").header(KEY, "test-server-only-key")
                        .param("q", "상협").param("provider", "google").param("pro", "true")
                        .param("cursor", "40").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(12))
                .andExpect(jsonPath("$.items[0].provider").value("google"))
                .andExpect(jsonPath("$.items[0].platform").value("ANDROID"))
                .andExpect(jsonPath("$.nextCursor").value(12));
        mockMvc.perform(get("/admin/ops/users/99").header(KEY, "test-server-only-key")).andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/ops/subscriptions").header(KEY, "test-server-only-key")
                        .param("status", SubscriptionStatus.ACTIVE.name()).param("store", SubscriptionStore.APP_STORE.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
