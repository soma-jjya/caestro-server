package com.caestro.server.domain.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.caestro.server.domain.b2b.repository.B2bProjectRepository;
import com.caestro.server.domain.inquiry.dto.request.CreateInquiryRequest;
import com.caestro.server.domain.inquiry.dto.request.UpdateInquiryRequest;
import com.caestro.server.domain.inquiry.dto.response.InquiryPageResponse;
import com.caestro.server.domain.inquiry.dto.response.InquiryResponse;
import com.caestro.server.domain.inquiry.entity.Inquiry;
import com.caestro.server.domain.inquiry.enums.ContactMethod;
import com.caestro.server.domain.inquiry.enums.InquiryStatus;
import com.caestro.server.domain.inquiry.enums.InquiryType;
import com.caestro.server.domain.inquiry.repository.InquiryRepository;
import com.caestro.server.domain.inquiry.service.InquiryService;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 실제 쿼리로 접수 저장·커서 페이지·상태/유형 필터·개수·부분 수정을 검증한다 (H2). */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"}, showSql = false)
@ContextConfiguration(classes = InquiryPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InquiryPersistenceTest {

    static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EntityScan(basePackageClasses = Inquiry.class)
    @EnableJpaRepositories(basePackageClasses = InquiryRepository.class)
    @Import(InquiryService.class)
    static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }

    @Autowired InquiryService service;
    @Autowired InquiryRepository repository;
    @MockitoBean B2bProjectRepository projects;
    @MockitoBean RedisRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        given(rateLimiter.tryAcquire(anyString(), anyInt(), any())).willReturn(true);
    }

    @Test
    @DisplayName("접수: 폼 값이 그대로 남고 빈 선택 항목은 null, 상태 NEW, IP·UA 흔적")
    void submitStoresForm() {
        Long id = service.submit(request(InquiryType.CAFE, "디저트 카페", "b2b_cafe"), "203.0.113.9", "Mozilla/5.0").id();

        Inquiry saved = repository.findById(id).orElseThrow();
        assertThat(saved.getType()).isEqualTo(InquiryType.CAFE);
        assertThat(saved.getOrganization()).isEqualTo("디저트 카페");
        assertThat(saved.getContactMethod()).isEqualTo(ContactMethod.PHONE);
        assertThat(saved.getPhone()).isEqualTo("010-1234-5678");
        assertThat(saved.getEmail()).isNull();
        assertThat(saved.getInstagram()).isNull();
        assertThat(saved.getUtmContent()).isEqualTo("b2b_cafe");
        assertThat(saved.getUtmTerm()).isNull();
        assertThat(saved.isPrivacyAgreed()).isTrue();
        assertThat(saved.isMarketingAgreed()).isFalse();
        assertThat(saved.getStatus()).isEqualTo(InquiryStatus.NEW);
        assertThat(saved.getClientIp()).isEqualTo("203.0.113.9");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getHandledAt()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("연락 방법: 이메일·인스타만 남긴 문의도 저장되고(phone null), 응답에 방법과 값이 실린다")
    void submitWithoutPhone() {
        Long email = service.submit(new CreateInquiryRequest(InquiryType.CAFE, "메일 카페", "담당자", ContactMethod.EMAIL,
                null, "owner@example.com", null, null, null, null, null, null, true, null, null, "/b2b/", null), "ip", null).id();
        Long insta = service.submit(new CreateInquiryRequest(InquiryType.CAFE, "인스타 카페", "담당자", ContactMethod.INSTAGRAM,
                " ", null, "cafe_peak", null, null, null, "https://www.instagram.com/cafe_peak", null, true, null, null,
                "/b2b/", null), "ip", null).id();

        InquiryResponse byEmail = service.get(email);
        assertThat(byEmail.contactMethod()).isEqualTo(ContactMethod.EMAIL);
        assertThat(byEmail.phone()).isNull();
        assertThat(byEmail.email()).isEqualTo("owner@example.com");
        InquiryResponse byInsta = service.get(insta);
        assertThat(byInsta.contactMethod()).isEqualTo(ContactMethod.INSTAGRAM);
        assertThat(byInsta.phone()).isNull();
        assertThat(byInsta.instagram()).isEqualTo("cafe_peak");
        assertThat(byInsta.website()).isEqualTo("https://www.instagram.com/cafe_peak");
    }

    @Test
    @DisplayName("목록: 최신순 커서 페이지, 상태·유형 필터, 상태별 개수")
    void listWithCursorAndFilters() {
        Long a = service.submit(request(InquiryType.CAFE, "A", null), "ip", null).id();
        Long b = service.submit(request(InquiryType.FESTIVAL, "B", null), "ip", null).id();
        Long c = service.submit(request(InquiryType.CAFE, "C", null), "ip", null).id();
        service.update(b, new UpdateInquiryRequest(InquiryStatus.CONTACTED, null, null));

        InquiryPageResponse first = service.list(null, null, null, 2);
        assertThat(first.items()).extracting(InquiryResponse::id).containsExactly(c, b);
        assertThat(first.nextCursor()).isEqualTo(b);
        InquiryPageResponse second = service.list(null, null, first.nextCursor(), 2);
        assertThat(second.items()).extracting(InquiryResponse::id).containsExactly(a);
        assertThat(second.nextCursor()).isNull();

        assertThat(service.list(InquiryStatus.NEW, null, null, null).items()).extracting(InquiryResponse::id).containsExactly(c, a);
        assertThat(service.list(null, InquiryType.FESTIVAL, null, null).items()).extracting(InquiryResponse::id).containsExactly(b);
        assertThat(service.list(InquiryStatus.NEW, InquiryType.CAFE, null, null).items()).hasSize(2);
        assertThat(service.count(InquiryStatus.NEW).count()).isEqualTo(2);
        assertThat(service.count(null).count()).isEqualTo(3);
    }

    @Test
    @DisplayName("수정: NEW 를 처음 벗어날 때만 handledAt, 메모·프로젝트 연결은 보낸 것만, 빈 문자열은 해제")
    void updatePartial() {
        Long id = service.submit(request(InquiryType.TOURISM, "관광공사", null), "ip", null).id();
        given(projects.existsById("bp_1")).willReturn(true);

        InquiryResponse contacted = service.update(id, new UpdateInquiryRequest(InquiryStatus.CONTACTED, "전화함", "bp_1"));
        assertThat(contacted.status()).isEqualTo(InquiryStatus.CONTACTED);
        assertThat(contacted.adminMemo()).isEqualTo("전화함");
        assertThat(contacted.linkedProjectId()).isEqualTo("bp_1");
        LocalDateTime handledAt = contacted.handledAt();
        assertThat(handledAt).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        // 상태만 바꾸면 메모·연결은 그대로, handledAt 도 그대로
        InquiryResponse won = service.update(id, new UpdateInquiryRequest(InquiryStatus.WON, null, null));
        assertThat(won.adminMemo()).isEqualTo("전화함");
        assertThat(won.linkedProjectId()).isEqualTo("bp_1");
        assertThat(won.handledAt()).isEqualTo(handledAt);

        InquiryResponse unlinked = service.update(id, new UpdateInquiryRequest(null, null, ""));
        assertThat(unlinked.linkedProjectId()).isNull();
        assertThat(unlinked.status()).isEqualTo(InquiryStatus.WON);
    }

    static CreateInquiryRequest request(InquiryType type, String organization, String utmContent) {
        CreateInquiryRequest.Utm utm = utmContent == null ? null
                : new CreateInquiryRequest.Utm("meta", "paid_social", "b2b_1102", utmContent, " ");
        return new CreateInquiryRequest(type, organization, "담당자", null, "010-1234-5678", " ", null, null, null, null,
                null, null, true, null, utm, "/b2b/", null);
    }
}
