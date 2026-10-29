package com.caestro.server.domain.fill.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.fill.dto.response.FillQueueItemResponse;
import com.caestro.server.domain.fill.dto.response.FillResponse;
import com.caestro.server.domain.fill.dto.response.FillSlotResponse;
import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.domain.fill.service.FillWriteGuard;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.config.WebMvcConfig;
import com.caestro.server.global.jwt.JwtProvider;
import com.caestro.server.global.security.CustomUserDetails;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 세션 조회 폴링(#205): 같은 상태면 304 로 본문 없이 끝나고, 칸·대기열이 바뀌면 ETag 가 달라져 200 이 온다.
 * ETag 는 응답 JSON 의 해시라 어떤 필드가 바뀌어도 잡힌다 — 변경 종류별로 서비스 코드를 손대지 않아도 된다.
 */
@WebMvcTest(controllers = FillController.class)
@Import({SecurityConfig.class, WebMvcConfig.class})
class FillSessionEtagWebTest {

    private static final CustomUserDetails OWNER = new CustomUserDetails(1L, User.Role.USER);
    private static final Instant T0 = Instant.parse("2026-10-29T03:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private FillService fillService;
    @MockitoBean private FillWriteGuard writeGuard;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private RedisTemplate<String, String> redisTemplate;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private static FillSlotResponse empty(int index) {
        return new FillSlotResponse(index, null, false, null, null, null);
    }

    private static FillSlotResponse filled(int index) {
        return new FillSlotResponse(index, "https://api.peakpic.app/fills/K7X2MQ/images/img" + index, false, "친구", T0, null);
    }

    private static FillResponse session(List<FillSlotResponse> slots, List<FillQueueItemResponse> queue) {
        return new FillResponse("K7X2MQ", true, "Black", "Strip", null, null, "제목", false, slots, null,
                T0.plusSeconds(7 * 86_400), T0, queue);
    }

    @Test
    @DisplayName("같은 상태를 다시 물으면 304, 본문 없음. 첫 응답에는 ETag 와 no-cache")
    void unchanged_is304() throws Exception {
        given(fillService.get("K7X2MQ", 1L)).willReturn(session(List.of(empty(0), empty(1), empty(2), empty(3)), List.of()));

        MvcResult first = mockMvc.perform(get("/fills/K7X2MQ").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn();
        String etag = first.getResponse().getHeader("ETag");

        mockMvc.perform(get("/fills/K7X2MQ").with(user(OWNER)).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag))
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("칸이 채워지거나 대기열이 바뀌면 ETag 가 달라져 200 으로 새 상태가 온다")
    void changed_is200WithNewEtag() throws Exception {
        given(fillService.get("K7X2MQ", 1L)).willReturn(session(List.of(empty(0), empty(1), empty(2), empty(3)), List.of()));
        String before = mockMvc.perform(get("/fills/K7X2MQ").with(user(OWNER))).andReturn().getResponse().getHeader("ETag");

        // 칸 0 이 채워짐
        given(fillService.get("K7X2MQ", 1L)).willReturn(session(List.of(filled(0), empty(1), empty(2), empty(3)), List.of()));
        String afterSlot = mockMvc.perform(get("/fills/K7X2MQ").with(user(OWNER)).header("If-None-Match", before))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        org.assertj.core.api.Assertions.assertThat(afterSlot).isNotEqualTo(before);

        // 대기열에 사진이 하나 밀려남(칸은 그대로)
        FillQueueItemResponse queued = new FillQueueItemResponse(9L, "https://api.peakpic.app/fills/K7X2MQ/images/q", null, 0, false, "친구", T0, T0.plusSeconds(5));
        given(fillService.get("K7X2MQ", 1L)).willReturn(session(List.of(filled(0), empty(1), empty(2), empty(3)), List.of(queued)));
        mockMvc.perform(get("/fills/K7X2MQ").with(user(OWNER)).header("If-None-Match", afterSlot))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", org.hamcrest.Matchers.not(afterSlot)));
    }
}
