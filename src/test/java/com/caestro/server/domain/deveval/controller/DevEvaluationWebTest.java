package com.caestro.server.domain.deveval.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.deveval.dto.response.DevEvaluationPageResponse;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationSavedResponse;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationStatsResponse;
import com.caestro.server.domain.deveval.entity.DevEvaluationPhoto;
import com.caestro.server.domain.deveval.service.DevEvaluationService;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.global.config.ClockConfig;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.config.WebMvcConfig;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.jwt.JwtProvider;
import com.caestro.server.global.security.CustomUserDetails;
import java.nio.charset.StandardCharsets;
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
 * 개발자 평가(contracts/dev-evaluations.md) 웹 계층: 올리기는 앱 토큰만(스튜디오 키로는 안 됨), 열람은 스튜디오 키·ADMIN 만(앱 토큰 403),
 * 기록·사진 본문 상한은 서비스 전에 413/411, 사진은 private 캐시로, CSV 는 text/csv 첨부로.
 */
@WebMvcTest(controllers = {DevEvaluationController.class, DevEvaluationAdminController.class})
@Import({SecurityConfig.class, WebMvcConfig.class, ClockConfig.class})
@TestPropertySource(properties = {"catalog.studio-key=test-studio-key", "dev-eval.max-record-bytes=512", "dev-eval.max-photo-bytes=1024"})
class DevEvaluationWebTest {

    static final String KEY = "X-Studio-Key";
    static final String STUDIO_KEY = "test-studio-key";
    static final CustomUserDetails APP_USER = new CustomUserDetails(7L, User.Role.USER);
    static final CustomUserDetails ADMIN = new CustomUserDetails(1L, User.Role.ADMIN);
    static final String CLIENT_ID = "0f8c2a9e-4b1d-4c3a-9e7f-1a2b3c4d5e6f";
    static final String BODY = "{\"rating\":23,\"ratingMax\":30,\"guide\":true,\"mediaType\":\"photo\",\"ratedAt\":\"2026-11-10T07:30:00Z\","
            + "\"photoScore\":{\"total\":78,\"pose\":84,\"place\":0.97,\"shape\":null},\"extra\":{\"k\":1}}";

    @Autowired MockMvc mockMvc;
    @MockitoBean DevEvaluationService evaluationService;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean RedisTemplate<String, String> redisTemplate;
    @MockitoBean JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("앱 올리기: 토큰 없으면 403, 스튜디오 키로도 403, 앱 토큰이면 200 {id, hasPhoto} 와 photoScore·extra 가 서비스로")
    void upsert_access() throws Exception {
        given(evaluationService.upsert(eq(7L), eq(CLIENT_ID), any())).willReturn(new DevEvaluationSavedResponse(CLIENT_ID, false));

        mockMvc.perform(put("/dev/evaluations/" + CLIENT_ID).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/dev/evaluations/" + CLIENT_ID).header(KEY, STUDIO_KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(evaluationService);

        mockMvc.perform(put("/dev/evaluations/" + CLIENT_ID).with(user(APP_USER)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CLIENT_ID))
                .andExpect(jsonPath("$.hasPhoto").value(false));
        verify(evaluationService).upsert(eq(7L), eq(CLIENT_ID), org.mockito.ArgumentMatchers.argThat(r ->
                r.rating() == 23 && r.photoScore() != null && r.photoScore().place() == 0.97 && r.photoScore().shape() == null
                        && Integer.valueOf(1).equals(r.extra().get("k"))));
    }

    @Test
    @DisplayName("남의 clientId 403·상한 409·값 오류 400 은 그 문구로")
    void upsert_errors() throws Exception {
        given(evaluationService.upsert(eq(7L), eq("other-users-id"), any())).willThrow(new CustomException(ErrorCode.DEV_EVAL_FORBIDDEN));
        given(evaluationService.upsert(eq(7L), eq("limit-reached-id"), any())).willThrow(new CustomException(ErrorCode.DEV_EVAL_LIMIT));
        given(evaluationService.upsert(eq(7L), eq("bad-rating-id1"), any()))
                .willThrow(new CustomException(ErrorCode.DEV_EVAL_INVALID, "rating 은 1~ratingMax(30) 사이 정수여야 합니다"));

        mockMvc.perform(put("/dev/evaluations/other-users-id").with(user(APP_USER)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(ErrorCode.DEV_EVAL_FORBIDDEN.getMessage()));
        mockMvc.perform(put("/dev/evaluations/limit-reached-id").with(user(APP_USER)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.DEV_EVAL_LIMIT.getMessage()));
        mockMvc.perform(put("/dev/evaluations/bad-rating-id1").with(user(APP_USER)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("평가 기록 값이 올바르지 않습니다: rating 은 1~ratingMax(30) 사이 정수여야 합니다"));
    }

    @Test
    @DisplayName("본문 상한: 기록 JSON 512B·사진 1KB 를 넘는 Content-Length 는 서비스 전에 413, 길이 없는 사진은 411")
    void sizeGuard() throws Exception {
        mockMvc.perform(put("/dev/evaluations/" + CLIENT_ID).with(user(APP_USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + "x".repeat(600) + "\"}"))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.message").value(ErrorCode.DEV_EVAL_TOO_LARGE.getMessage()));
        mockMvc.perform(put("/dev/evaluations/" + CLIENT_ID + "/photo").with(user(APP_USER))
                        .contentType(MediaType.IMAGE_JPEG).content(new byte[1025]))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.message").value(ErrorCode.DEV_EVAL_PHOTO_TOO_LARGE.getMessage()));
        mockMvc.perform(put("/dev/evaluations/" + CLIENT_ID + "/photo").with(user(APP_USER)).contentType(MediaType.IMAGE_JPEG))
                .andExpect(status().is(411));
        verifyNoInteractions(evaluationService);

        // 사진 상한 안이면 기록 상한(512B)보다 커도 통과
        mockMvc.perform(put("/dev/evaluations/" + CLIENT_ID + "/photo").with(user(APP_USER))
                        .contentType(MediaType.IMAGE_JPEG).content(new byte[900]))
                .andExpect(status().isNoContent());
        verify(evaluationService).uploadPhoto(eq(7L), eq(CLIENT_ID), any(byte[].class), org.mockito.ArgumentMatchers.startsWith("image/jpeg"));
    }

    @Test
    @DisplayName("열람(/admin/dev/**)은 토큰·키 없음, 틀린 키, 앱 사용자 토큰이면 403. 스튜디오 키와 ADMIN 은 200")
    void admin_access() throws Exception {
        given(evaluationService.page(any(), any(), any(), any())).willReturn(new DevEvaluationPageResponse(List.of(), 0, 50, 0));
        given(evaluationService.stats()).willReturn(new DevEvaluationStatsResponse(0, 0, 0,
                new DevEvaluationStatsResponse.Group(0, null), new DevEvaluationStatsResponse.Group(0, null), null));

        for (String path : List.of("/admin/dev/evaluations", "/admin/dev/evaluations/stats", "/admin/dev/evaluations.csv",
                "/admin/dev/evaluations/" + CLIENT_ID + "/photo")) {
            mockMvc.perform(get(path)).andExpect(status().isForbidden());
            mockMvc.perform(get(path).header(KEY, "wrong")).andExpect(status().isForbidden());
            mockMvc.perform(get(path).with(user(APP_USER))).andExpect(status().isForbidden());
        }
        mockMvc.perform(delete("/admin/dev/evaluations/" + CLIENT_ID).with(user(APP_USER))).andExpect(status().isForbidden());
        verifyNoInteractions(evaluationService);

        mockMvc.perform(get("/admin/dev/evaluations").header(KEY, STUDIO_KEY)
                        .param("page", "2").param("size", "20").param("guide", "false").param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(0));
        verify(evaluationService).page(2, 20, false, 7L);
        mockMvc.perform(get("/admin/dev/evaluations/stats").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guide.count").value(0));
        mockMvc.perform(delete("/admin/dev/evaluations/" + CLIENT_ID).header(KEY, STUDIO_KEY)).andExpect(status().isNoContent());
        verify(evaluationService).delete(CLIENT_ID);
    }

    @Test
    @DisplayName("사진은 올린 형식 그대로, Cache-Control private max-age=300. 없으면 404")
    void admin_photo() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        given(evaluationService.photo(CLIENT_ID)).willReturn(new DevEvaluationPhoto(CLIENT_ID, "image/jpeg", jpeg));
        given(evaluationService.photo("no-photo-id")).willThrow(new CustomException(ErrorCode.DEV_EVAL_PHOTO_NOT_FOUND));

        mockMvc.perform(get("/admin/dev/evaluations/" + CLIENT_ID + "/photo").header(KEY, STUDIO_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", "max-age=300, private"))
                .andExpect(content().bytes(jpeg));
        mockMvc.perform(get("/admin/dev/evaluations/no-photo-id/photo").header(KEY, STUDIO_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CSV 는 text/csv;charset=UTF-8 첨부(파일 이름에 날짜), 거르기는 서비스로")
    void admin_csv() throws Exception {
        byte[] csv = "﻿id,userId\r\n".getBytes(StandardCharsets.UTF_8);
        given(evaluationService.csv(isNull(), isNull())).willReturn(csv);
        given(evaluationService.csv(eq(true), eq(7L))).willReturn(csv);

        mockMvc.perform(get("/admin/dev/evaluations.csv").header(KEY, STUDIO_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.matchesPattern(
                        "attachment; filename=\"peakpic-dev-evaluations-\\d{8}\\.csv\"")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(csv));
        mockMvc.perform(get("/admin/dev/evaluations.csv").header(KEY, STUDIO_KEY).param("guide", "true").param("userId", "7"))
                .andExpect(status().isOk());
        verify(evaluationService).csv(eq(true), eq(7L));
        verify(evaluationService).csv(isNull(), isNull());
    }
}
