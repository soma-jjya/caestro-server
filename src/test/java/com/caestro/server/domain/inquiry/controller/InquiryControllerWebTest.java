package com.caestro.server.domain.inquiry.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.inquiry.dto.request.CreateInquiryRequest;
import com.caestro.server.domain.inquiry.dto.response.InquiryCreatedResponse;
import com.caestro.server.domain.inquiry.enums.ContactMethod;
import com.caestro.server.domain.inquiry.service.InquiryService;
import com.caestro.server.global.config.SecurityConfig;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 공개 접수(#216): 토큰 없이 201, 필수·동의·연락 방법 검증은 400, XFF 의 첫 값이 IP, 한도 초과는 429. */
@WebMvcTest(controllers = InquiryController.class)
@Import(SecurityConfig.class)
class InquiryControllerWebTest {

    static final String VALID = """
            {"type":"CAFE","organization":"디저트 카페","contactName":"김담당","phone":"010-1234-5678",
             "privacyAgreed":true,"utm":{"source":"meta","content":"b2b_cafe"},"landingPath":"/b2b/"}
            """;

    @Autowired MockMvc mockMvc;
    @MockitoBean InquiryService inquiryService;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean RedisTemplate<String, String> redisTemplate;
    @MockitoBean JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("토큰 없이 201. XFF 첫 값과 User-Agent 가 서비스로 간다")
    void submitWithoutToken() throws Exception {
        given(inquiryService.submit(any(), eq("203.0.113.9"), eq("UA/1"))).willReturn(new InquiryCreatedResponse(42L));

        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON).content(VALID)
                        .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1").header("User-Agent", "UA/1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42));
    }

    @Test
    @DisplayName("필수 누락·형식 오류·동의 없음은 400 이고 서비스에 닿지 않는다")
    void validation() throws Exception {
        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CAFE\",\"organization\":\"x\",\"contactName\":\"y\",\"privacyAgreed\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("선택한 연락 방법의 연락처를 입력해 주세요"));

        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("\"privacyAgreed\":true", "\"privacyAgreed\":false")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("개인정보 수집·이용에 동의해야 합니다"));

        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("010-1234-5678", "abc")))
                .andExpect(status().isBadRequest());

        verify(inquiryService, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("연락 방법: 고른 칸 하나만 있으면 201 — 전화·이메일·인스타, 방법 없는 옛 폼은 전화로")
    void contactMethodAccepted() throws Exception {
        given(inquiryService.submit(any(), any(), any())).willReturn(new InquiryCreatedResponse(1L));
        String base = """
                {"type":"CAFE","organization":"디저트 카페","contactName":"김담당","privacyAgreed":true,%s}
                """;

        for (String contact : new String[]{
                "\"contactMethod\":\"PHONE\",\"phone\":\"01012345678\"",
                "\"contactMethod\":\"EMAIL\",\"email\":\"owner@example.com\"",
                "\"contactMethod\":\"INSTAGRAM\",\"instagram\":\"cafe_peak\",\"website\":\"https://www.instagram.com/cafe_peak\"",
                "\"phone\":\"010-1234-5678\""}) {
            mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON).content(base.formatted(contact)))
                    .andExpect(status().isCreated());
        }

        ArgumentCaptor<CreateInquiryRequest> sent = ArgumentCaptor.forClass(CreateInquiryRequest.class);
        verify(inquiryService, times(4)).submit(sent.capture(), any(), any());
        assertThat(sent.getAllValues()).extracting(CreateInquiryRequest::effectiveContactMethod)
                .containsExactly(ContactMethod.PHONE, ContactMethod.EMAIL, ContactMethod.INSTAGRAM, ContactMethod.PHONE);
    }

    @Test
    @DisplayName("연락 방법: 고른 칸이 비었거나 인스타 아이디 형식이 틀리면 400")
    void contactMethodRejected() throws Exception {
        String base = """
                {"type":"CAFE","organization":"디저트 카페","contactName":"김담당","privacyAgreed":true,%s}
                """;

        // 이메일을 골랐는데 전화만 있다
        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content(base.formatted("\"contactMethod\":\"EMAIL\",\"phone\":\"01012345678\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("선택한 연락 방법의 연락처를 입력해 주세요"));
        // 인스타를 골랐는데 칸이 공백
        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content(base.formatted("\"contactMethod\":\"INSTAGRAM\",\"instagram\":\" \"")))
                .andExpect(status().isBadRequest());
        // @·대문자·연속 마침표·끝 마침표·31자는 아이디가 아니다
        for (String bad : new String[]{"@cafe_peak", "Cafe_Peak", "cafe..peak", "cafe.", "a".repeat(31)}) {
            mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                            .content(base.formatted("\"contactMethod\":\"INSTAGRAM\",\"instagram\":\"" + bad + "\"")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("인스타그램 아이디 형식이 올바르지 않습니다"));
        }

        verify(inquiryService, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("한도 초과는 429 공통 오류 형식")
    void rateLimited() throws Exception {
        given(inquiryService.submit(any(), any(), any())).willThrow(new CustomException(ErrorCode.TOO_MANY_REQUESTS));

        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));
    }
}
