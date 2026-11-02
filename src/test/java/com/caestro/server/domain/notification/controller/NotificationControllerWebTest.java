package com.caestro.server.domain.notification.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.notification.dto.response.NotificationPageResponse;
import com.caestro.server.domain.notification.dto.response.NotificationResponse;
import com.caestro.server.domain.notification.dto.response.UnreadCountResponse;
import com.caestro.server.domain.notification.service.NotificationService;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.jwt.JwtProvider;
import com.caestro.server.global.security.CustomUserDetails;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 알림함 API(#211): JWT 가 있어야 열리고, 커서·크기는 쿼리로, 남의 알림 읽음은 404. */
@WebMvcTest(controllers = NotificationController.class)
@Import(SecurityConfig.class)
class NotificationControllerWebTest {

    private static final CustomUserDetails ME = new CustomUserDetails(7L, User.Role.USER);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private RedisTemplate<String, String> redisTemplate;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("목록: cursor·size 를 넘기면 항목과 nextCursor 를 돌려주고, 토큰 없이는 열리지 않는다")
    void list() throws Exception {
        given(notificationService.list(7L, 20L, 2)).willReturn(new NotificationPageResponse(List.of(
                new NotificationResponse(19L, "fill_completed", "네컷이 완성됐어요", "본문", Map.of("code", "K7X2MQ"),
                        LocalDateTime.now(), null)), 19L));

        mockMvc.perform(get("/notifications").with(user(ME)).param("cursor", "20").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(19))
                .andExpect(jsonPath("$.items[0].data.code").value("K7X2MQ"))
                .andExpect(jsonPath("$.items[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value(19));

        mockMvc.perform(get("/notifications")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("안 읽은 개수, 하나 읽음(204 / 남의 것 404), 모두 읽음(204)")
    void unreadAndRead() throws Exception {
        given(notificationService.unreadCount(7L)).willReturn(new UnreadCountResponse(3));
        mockMvc.perform(get("/notifications/unread-count").with(user(ME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(3));

        mockMvc.perform(patch("/notifications/5/read").with(user(ME))).andExpect(status().isNoContent());
        verify(notificationService).markRead(7L, 5L);

        willThrow(new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND)).given(notificationService).markRead(7L, 6L);
        mockMvc.perform(patch("/notifications/6/read").with(user(ME))).andExpect(status().isNotFound());

        mockMvc.perform(patch("/notifications/read-all").with(user(ME))).andExpect(status().isNoContent());
        verify(notificationService).markAllRead(7L);
    }
}
