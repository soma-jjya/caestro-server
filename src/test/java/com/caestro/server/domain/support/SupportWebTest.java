package com.caestro.server.domain.support;

import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.domain.support.controller.SupportAdminController;
import com.caestro.server.domain.support.controller.SupportController;
import com.caestro.server.domain.support.dto.response.SupportTicketPageResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketResponse;
import com.caestro.server.domain.support.enums.SupportCategory;
import com.caestro.server.domain.support.enums.SupportStatus;
import com.caestro.server.domain.support.service.SupportAdminService;
import com.caestro.server.domain.support.service.SupportService;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.security.CustomUserDetails;
import com.caestro.server.global.jwt.JwtProvider;
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

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 앱 경로는 JWT 가 있어야, 관리자 경로는 스튜디오 키 또는 ADMIN 만. 검증·사진 302 는 컨트롤러 몫. */
@WebMvcTest(controllers = {SupportController.class, SupportAdminController.class},
        properties = "catalog.studio-key=test-server-only-key")
@Import(SecurityConfig.class)
class SupportWebTest {

    static final String KEY = "X-Studio-Key";
    static final CustomUserDetails ME = new CustomUserDetails(7L, User.Role.USER);

    @Autowired MockMvc mockMvc;
    @MockitoBean SupportService supportService;
    @MockitoBean SupportAdminService adminService;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean RedisTemplate<String, String> redisTemplate;
    @MockitoBean JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("앱 경로: 토큰 없으면 403, 있으면 201 과 상세. 제목이 비면 400")
    void userRoutes() throws Exception {
        given(supportService.create(eq(7L), any())).willReturn(detail(12L));
        String body = """
                {"category":"BUG","title":"사진이 안 올라가요","body":"멈춰요","appVersion":"1.4.2","platform":"ANDROID"}
                """;

        mockMvc.perform(post("/support/tickets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/support/tickets").with(user(ME)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(post("/support/tickets").with(user(ME)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"BUG\",\"title\":\"\",\"body\":\"x\"}"))
                .andExpect(status().isBadRequest());

        given(supportService.list(eq(7L), isNull(), isNull())).willReturn(new SupportTicketPageResponse(List.of(), null));
        mockMvc.perform(get("/support/tickets").with(user(ME))).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("관리자 경로: 키 없음·일반 사용자 403, 스튜디오 키와 ADMIN 200. 상태 ANSWERED 는 400")
    void adminRoutes() throws Exception {
        given(adminService.list(any(), any(), any(), any())).willReturn(new SupportTicketPageResponse(List.of(), null));

        mockMvc.perform(get("/admin/support/tickets")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/support/tickets").header(KEY, "wrong")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/support/tickets").with(user(ME))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/support/tickets").header(KEY, "test-server-only-key")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/support/tickets?status=OPEN").with(user(new CustomUserDetails(2L, User.Role.ADMIN))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/admin/support/tickets/12").header(KEY, "test-server-only-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ANSWERED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("사진: 서명 주소가 있으면 302(캐시 금지), 없으면 바이트를 그 Content-Type 으로")
    void attachment() throws Exception {
        given(supportService.attachmentRedirect(7L, 12L, 3L)).willReturn(Optional.of(URI.create("https://signed.example/x")));
        mockMvc.perform(get("/support/tickets/12/attachments/3").with(user(ME)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://signed.example/x"))
                .andExpect(header().string("Cache-Control", "no-store"));

        given(adminService.attachmentRedirect(anyLong(), anyLong())).willReturn(Optional.empty());
        given(adminService.attachment(12L, 3L)).willReturn(new StoredImage("s", "image/png", new byte[]{1, 2, 3}));
        mockMvc.perform(get("/admin/support/tickets/12/attachments/3").header(KEY, "test-server-only-key"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));

        // 사진 올리기: 바이트 본문
        given(supportService.addAttachment(eq(7L), eq(12L), any(byte[].class), any()))
                .willReturn(new com.caestro.server.domain.support.dto.response.SupportAttachmentResponse(3L, "u", "image/png", 3, LocalDateTime.now()));
        mockMvc.perform(post("/support/tickets/12/attachments").with(user(ME)).contentType(MediaType.IMAGE_PNG).content(new byte[]{1, 2, 3}))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3));
    }

    static SupportTicketResponse detail(long id) {
        return new SupportTicketResponse(id, SupportCategory.BUG, "사진이 안 올라가요", SupportStatus.OPEN, false,
                "1.4.2", "ANDROID", null, null, LocalDateTime.now(), LocalDateTime.now(), List.of(), List.of());
    }
}
