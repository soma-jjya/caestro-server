package com.caestro.server.domain.b2b;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.caestro.server.domain.b2b.controller.B2bAdminController;
import com.caestro.server.domain.b2b.controller.B2bPublicController;
import com.caestro.server.domain.b2b.service.B2bFillService;
import com.caestro.server.domain.b2b.service.B2bImageService;
import com.caestro.server.domain.b2b.service.B2bProjectService;
import com.caestro.server.domain.b2b.service.B2bProjectDeletionService;
import com.caestro.server.domain.fill.service.FillWriteGuard;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.jwt.JwtProvider;
import com.caestro.server.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {B2bAdminController.class, B2bPublicController.class},
        properties = "catalog.studio-key=test-server-only-key")
@Import(SecurityConfig.class)
class B2bSecurityWebTest {
    private static final CustomUserDetails GUEST = new CustomUserDetails(1L, User.Role.USER);
    @Autowired MockMvc mvc;
    @MockitoBean B2bProjectService projects;
    @MockitoBean B2bProjectDeletionService deletion;
    @MockitoBean B2bFillService fills;
    @MockitoBean B2bImageService images;
    @MockitoBean FillWriteGuard guard;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean RedisTemplate<String, String> redisTemplate;
    @MockitoBean JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void entryIsPublicButAdminNeedsKeyOrAdminRole() throws Exception {
        mvc.perform(get("/b2b/projects/yuja")).andExpect(status().isOk());
        mvc.perform(get("/b2b/admin/projects")).andExpect(status().isForbidden());
        mvc.perform(get("/b2b/admin/projects").with(user(GUEST))).andExpect(status().isForbidden());
        mvc.perform(get("/b2b/admin/projects").header("X-Studio-Key", "wrong")).andExpect(status().isForbidden());
        mvc.perform(get("/b2b/admin/projects").header("X-Studio-Key", "test-server-only-key")).andExpect(status().isOk());
        mvc.perform(get("/b2b/admin/projects").with(user(new CustomUserDetails(2L, User.Role.ADMIN))))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/b2b/projects/yuja/fills/ABCDEF", "/fills/ABCDEF", "/b2b/administrator/projects"})
    void keyCannotAuthenticateParticipantOrOtherPaths(String path) throws Exception {
        mvc.perform(get(path).header("X-Studio-Key", "test-server-only-key")).andExpect(status().isForbidden());
    }

    @Test
    void guestCreationUsesExistingWriteLimit() throws Exception {
        mvc.perform(post("/b2b/projects/yuja/fills").with(user(GUEST)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frameId\":\"bf_1\",\"frameVersion\":1}"))
                .andExpect(status().isCreated());
        var order = inOrder(guard, fills);
        order.verify(guard).checkCreate(1L);
        order.verify(fills).create(eq("yuja"), eq(1L), any());
    }

    @Test
    void creationLimitRejectsBeforeService() throws Exception {
        doThrow(new CustomException(ErrorCode.TOO_MANY_REQUESTS)).when(guard).checkCreate(1L);
        mvc.perform(post("/b2b/projects/yuja/fills").with(user(GUEST)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frameId\":\"bf_1\",\"frameVersion\":1}"))
                .andExpect(status().isTooManyRequests());
        verifyNoInteractions(fills);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"frameId\":\"bf_1\"}", "{\"frameId\":\"bf_1\",\"frameVersion\":0}"})
    void creationRequiresSelectedVersion(String body) throws Exception {
        mvc.perform(post("/b2b/projects/yuja/fills").with(user(GUEST)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(guard, fills);
    }

    @Test
    void invalidNestedFrameIsRejectedBeforeReplacement() throws Exception {
        mvc.perform(put("/b2b/admin/projects/bp_1/frames").header("X-Studio-Key", "test-server-only-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"frames\":[null]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(projects);
    }

    @Test
    void notPublishableKeepsConflictStatusAndErrorShape() throws Exception {
        when(projects.publish("bp_empty")).thenThrow(new CustomException(ErrorCode.B2B_NOT_PUBLISHABLE));
        mvc.perform(post("/b2b/admin/projects/bp_empty/publish").header("X-Studio-Key", "test-server-only-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void deleteRequiresStudioKeyOrAdminAndReturnsEmpty204() throws Exception {
        mvc.perform(delete("/b2b/admin/projects/bp_test")).andExpect(status().isForbidden());
        mvc.perform(delete("/b2b/admin/projects/bp_test").with(user(GUEST))).andExpect(status().isForbidden());
        mvc.perform(delete("/b2b/admin/projects/bp_test").header("X-Studio-Key", "wrong"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(deletion);
        mvc.perform(delete("/b2b/admin/projects/bp_test").header("X-Studio-Key", "test-server-only-key"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(delete("/b2b/admin/projects/bp_admin").with(user(new CustomUserDetails(2L, User.Role.ADMIN))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(deletion).delete("bp_test");
        verify(deletion).delete("bp_admin");
    }

    @Test
    void deleteKeepsNotFoundAndConflictErrorShape() throws Exception {
        doThrow(new CustomException(ErrorCode.B2B_PROJECT_NOT_FOUND)).when(deletion).delete("bp_missing");
        doThrow(new CustomException(ErrorCode.B2B_PROJECT_DELETE_NOT_ALLOWED)).when(deletion).delete("bp_live");
        mvc.perform(delete("/b2b/admin/projects/bp_missing").header("X-Studio-Key", "test-server-only-key"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").isString());
        mvc.perform(delete("/b2b/admin/projects/bp_live").header("X-Studio-Key", "test-server-only-key"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isString());
    }
}
