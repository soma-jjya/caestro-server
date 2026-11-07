package com.caestro.server.domain.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caestro.server.domain.inquiry.entity.Inquiry;
import com.caestro.server.domain.inquiry.enums.ContactMethod;
import com.caestro.server.domain.inquiry.enums.InquiryStatus;
import com.caestro.server.domain.inquiry.repository.InquiryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #216 — 폼 접수부터 관리자 조회·상태 변경까지 실제 MySQL·Redis 로 한 바퀴. 보안 체인(permitAll, 스튜디오 키)까지 같이 탄다.
 * 실제 MySQL·Redis 필요 (docker-compose up -d / CI 서비스 컨테이너).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = "catalog.studio-key=test-flow-key")
class InquiryFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired InquiryRepository repository;
    @Autowired ObjectMapper objectMapper;

    private Long createdId;

    @AfterEach
    void tearDown() {
        if (createdId != null) repository.deleteById(createdId);
    }

    @Test
    @DisplayName("접수(무인증 201) → 목록 NEW 필터에 보임 → PATCH 로 CONTACTED·handledAt → count 반영")
    void submitThenManage() throws Exception {
        // 같은 Redis 를 여러 번 돌려도 IP 창(5/시간)에 걸리지 않게 매번 다른 주소로
        String ip = "198.51.100." + ThreadLocalRandom.current().nextInt(1, 255);
        String body = objectMapper.readTree(mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", ip).header("User-Agent", "flow-test")
                        .content("""
                                {"type":"FESTIVAL","organization":"흐름 테스트 축제","contactName":"담당자","phone":"010-0000-0000",
                                 "email":"","privacyAgreed":true,"utm":{"source":"meta","content":"b2b_festival"},"landingPath":"/b2b/"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        createdId = Long.parseLong(body);
        assertThat(createdId).isPositive();

        Inquiry saved = repository.findById(createdId).orElseThrow();
        assertThat(saved.getClientIp()).isEqualTo(ip);
        assertThat(saved.getEmail()).isNull();
        assertThat(saved.getStatus()).isEqualTo(InquiryStatus.NEW);

        // 관리자: 키 없이는 403, 키가 있으면 NEW·FESTIVAL 필터의 첫 줄이 방금 것
        mockMvc.perform(get("/b2b/admin/inquiries")).andExpect(status().isForbidden());
        JsonNode page = objectMapper.readTree(mockMvc.perform(get("/b2b/admin/inquiries")
                        .header("X-Studio-Key", "test-flow-key").param("status", "NEW").param("type", "FESTIVAL").param("size", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.get("items").get(0).get("id").asLong()).isEqualTo(createdId);
        assertThat(page.get("items").get(0).get("utm").get("content").asText()).isEqualTo("b2b_festival");

        long newBefore = objectMapper.readTree(mockMvc.perform(get("/b2b/admin/inquiries/count")
                        .header("X-Studio-Key", "test-flow-key").param("status", "NEW"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("count").asLong();

        mockMvc.perform(patch("/b2b/admin/inquiries/" + createdId).header("X-Studio-Key", "test-flow-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONTACTED\",\"adminMemo\":\"전화함\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTACTED"))
                .andExpect(jsonPath("$.adminMemo").value("전화함"))
                .andExpect(jsonPath("$.handledAt").exists());

        long newAfter = objectMapper.readTree(mockMvc.perform(get("/b2b/admin/inquiries/count")
                        .header("X-Studio-Key", "test-flow-key").param("status", "NEW"))
                .andReturn().getResponse().getContentAsString()).get("count").asLong();
        assertThat(newAfter).isEqualTo(newBefore - 1);
    }

    @Test
    @DisplayName("인스타만 남긴 문의도 실제 DB 에 저장된다 — phone NULL 허용(216-b2b-inquiry-contact-method.sql)이 빠지면 여기서 실패")
    void submitInstagramOnly() throws Exception {
        String ip = "198.51.100." + ThreadLocalRandom.current().nextInt(1, 255);
        String body = objectMapper.readTree(mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", ip)
                        .content("""
                                {"type":"CAFE","organization":"흐름 테스트 카페","contactName":"담당자","contactMethod":"INSTAGRAM",
                                 "instagram":"flow_test.cafe","privacyAgreed":true,"landingPath":"/b2b/"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        createdId = Long.parseLong(body);

        Inquiry saved = repository.findById(createdId).orElseThrow();
        assertThat(saved.getContactMethod()).isEqualTo(ContactMethod.INSTAGRAM);
        assertThat(saved.getInstagram()).isEqualTo("flow_test.cafe");
        assertThat(saved.getPhone()).isNull();
    }
}
