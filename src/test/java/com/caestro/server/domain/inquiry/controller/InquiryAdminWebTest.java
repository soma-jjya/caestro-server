package com.caestro.server.domain.inquiry.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.inquiry.dto.request.UpdateInquiryRequest;
import com.caestro.server.domain.inquiry.dto.response.InquiryCountResponse;
import com.caestro.server.domain.inquiry.dto.response.InquiryPageResponse;
import com.caestro.server.domain.inquiry.dto.response.InquiryResponse;
import com.caestro.server.domain.inquiry.enums.ContactMethod;
import com.caestro.server.domain.inquiry.enums.InquiryStatus;
import com.caestro.server.domain.inquiry.enums.InquiryType;
import com.caestro.server.domain.inquiry.service.InquiryService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 관리 API(#216): 스튜디오 키 또는 ADMIN 만, 일반 사용자 토큰은 403. 필터·커서는 쿼리로, 부분 수정은 PATCH. */
@WebMvcTest(controllers = InquiryAdminController.class, properties = "catalog.studio-key=test-server-only-key")
@Import(SecurityConfig.class)
class InquiryAdminWebTest {

    static final String KEY = "X-Studio-Key";

    @Autowired MockMvc mockMvc;
    @MockitoBean InquiryService inquiryService;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean RedisTemplate<String, String> redisTemplate;
    @MockitoBean JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("키 없음·틀린 키·일반 사용자는 403, 맞는 키와 ADMIN 은 200")
    void access() throws Exception {
        given(inquiryService.list(any(), any(), any(), any())).willReturn(new InquiryPageResponse(List.of(), null));

        mockMvc.perform(get("/b2b/admin/inquiries")).andExpect(status().isForbidden());
        mockMvc.perform(get("/b2b/admin/inquiries").header(KEY, "wrong")).andExpect(status().isForbidden());
        mockMvc.perform(get("/b2b/admin/inquiries").with(user(new CustomUserDetails(1L, User.Role.USER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/b2b/admin/inquiries").header(KEY, "test-server-only-key")).andExpect(status().isOk());
        mockMvc.perform(get("/b2b/admin/inquiries").with(user(new CustomUserDetails(2L, User.Role.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("목록 필터·커서, 개수, 상세, 부분 수정, 없는 id 404")
    void endpoints() throws Exception {
        InquiryResponse row = new InquiryResponse(7L, InquiryType.FESTIVAL, "유자축제", "담당자", ContactMethod.INSTAGRAM, null, null,
                "yuja_fest", null,
                null, null, null, null, true, false, new InquiryResponse.Utm("meta", null, null, "b2b_festival", null),
                "/b2b/", "1.2.3.4", "UA", InquiryStatus.NEW, null, null, null, LocalDateTime.now(), LocalDateTime.now());
        given(inquiryService.list(InquiryStatus.NEW, InquiryType.FESTIVAL, 10L, 5)).willReturn(new InquiryPageResponse(List.of(row), 7L));
        given(inquiryService.count(InquiryStatus.NEW)).willReturn(new InquiryCountResponse(3));
        given(inquiryService.get(7L)).willReturn(row);
        given(inquiryService.get(8L)).willThrow(new CustomException(ErrorCode.INQUIRY_NOT_FOUND));
        given(inquiryService.update(eq(7L), any())).willReturn(row);

        mockMvc.perform(get("/b2b/admin/inquiries").header(KEY, "test-server-only-key")
                        .param("status", "NEW").param("type", "FESTIVAL").param("cursor", "10").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(7))
                .andExpect(jsonPath("$.items[0].utm.content").value("b2b_festival"))
                .andExpect(jsonPath("$.items[0].contactMethod").value("INSTAGRAM"))
                .andExpect(jsonPath("$.items[0].instagram").value("yuja_fest"))
                .andExpect(jsonPath("$.nextCursor").value(7));
        mockMvc.perform(get("/b2b/admin/inquiries/count").header(KEY, "test-server-only-key").param("status", "NEW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));
        mockMvc.perform(get("/b2b/admin/inquiries/7").header(KEY, "test-server-only-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.organization").value("유자축제"));
        mockMvc.perform(get("/b2b/admin/inquiries/8").header(KEY, "test-server-only-key")).andExpect(status().isNotFound());

        mockMvc.perform(patch("/b2b/admin/inquiries/7").header(KEY, "test-server-only-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONTACTED\",\"adminMemo\":\"전화함\"}"))
                .andExpect(status().isOk());
        verify(inquiryService).update(7L, new UpdateInquiryRequest(InquiryStatus.CONTACTED, "전화함", null));
    }
}
