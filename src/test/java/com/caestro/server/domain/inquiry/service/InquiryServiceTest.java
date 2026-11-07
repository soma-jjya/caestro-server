package com.caestro.server.domain.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.b2b.repository.B2bProjectRepository;
import com.caestro.server.domain.inquiry.dto.request.CreateInquiryRequest;
import com.caestro.server.domain.inquiry.dto.request.UpdateInquiryRequest;
import com.caestro.server.domain.inquiry.entity.Inquiry;
import com.caestro.server.domain.inquiry.enums.ContactMethod;
import com.caestro.server.domain.inquiry.enums.InquiryStatus;
import com.caestro.server.domain.inquiry.enums.InquiryType;
import com.caestro.server.domain.inquiry.event.InquiryReceivedEvent;
import com.caestro.server.domain.inquiry.repository.InquiryRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** 접수의 문지기 순서(한도 → 허니팟 → 저장)와 이벤트·지표, 수정 시 없는 프로젝트 404. */
@ExtendWith(MockitoExtension.class)
class InquiryServiceTest {

    @Mock InquiryRepository repository;
    @Mock B2bProjectRepository projects;
    @Mock RedisRateLimiter rateLimiter;
    @Mock ApplicationEventPublisher events;
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    InquiryService service;

    @BeforeEach
    void setUp() {
        service = new InquiryService(repository, projects, rateLimiter, events, meters,
                Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "perIpLimit", 5);
        ReflectionTestUtils.setField(service, "perIpWindowSeconds", 3600L);
        ReflectionTestUtils.setField(service, "dailyLimit", 300);
    }

    @Test
    @DisplayName("IP 한도를 넘으면 429, 저장도 전체 창 집계도 없다")
    void rateLimitedByIp() {
        given(rateLimiter.tryAcquire("rl:inquiry:ip:1.2.3.4", 5, Duration.ofSeconds(3600))).willReturn(false);

        assertThatThrownBy(() -> service.submit(request(null), "1.2.3.4", null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        verify(repository, never()).save(any());
        verify(rateLimiter, never()).tryAcquire(eq("rl:inquiry:all"), anyInt(), any());
        assertThat(meters.counter(InquiryService.METRIC_REJECTED, "reason", "rate_limit").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("허니팟이 차 있으면 201 모양(id 0)이지만 저장·이벤트가 없다")
    void honeypotSilentlyDropped() {
        given(rateLimiter.tryAcquire(any(), anyInt(), any())).willReturn(true);

        assertThat(service.submit(request("http://spam"), "1.2.3.4", null).id()).isZero();
        verify(repository, never()).save(any());
        verify(events, never()).publishEvent(any());
        assertThat(meters.counter(InquiryService.METRIC_REJECTED, "reason", "honeypot").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("정상 접수: 공백은 다듬고 UA 는 300자에서 자르며, 커밋 뒤 웹훅용 이벤트를 낸다")
    void submitPublishesEvent() {
        given(rateLimiter.tryAcquire(any(), anyInt(), any())).willReturn(true);
        given(repository.save(any())).willAnswer(inv -> {
            Inquiry i = inv.getArgument(0);
            ReflectionTestUtils.setField(i, "id", 42L);
            return i;
        });

        assertThat(service.submit(request(null), "1.2.3.4", "x".repeat(400)).id()).isEqualTo(42L);

        ArgumentCaptor<Inquiry> saved = ArgumentCaptor.forClass(Inquiry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrganization()).isEqualTo("고흥 유자축제");
        assertThat(saved.getValue().getUserAgent()).hasSize(300);
        assertThat(saved.getValue().getUtmContent()).isEqualTo("b2b_festival");
        ArgumentCaptor<InquiryReceivedEvent> event = ArgumentCaptor.forClass(InquiryReceivedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().id()).isEqualTo(42L);
        assertThat(event.getValue().utmContent()).isEqualTo("b2b_festival");
        // 방법이 없던 옛 폼 → 전화로 저장하고 이벤트 연락처도 전화번호
        assertThat(saved.getValue().getContactMethod()).isEqualTo(ContactMethod.PHONE);
        assertThat(event.getValue().contactMethod()).isEqualTo(ContactMethod.PHONE);
        assertThat(event.getValue().contact()).isEqualTo("010-1234-5678");
        assertThat(meters.counter(InquiryService.METRIC_RECEIVED, "type", "FESTIVAL").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("인스타를 고르면 방법·아이디를 저장하고 이벤트 연락처도 아이디, 빈 전화 칸은 null")
    void submitByInstagram() {
        given(rateLimiter.tryAcquire(any(), anyInt(), any())).willReturn(true);
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.submit(new CreateInquiryRequest(InquiryType.CAFE, "카페", "담당자", ContactMethod.INSTAGRAM, "", null,
                "cafe_peak", null, null, null, null, null, true, false, null, null, null), "1.2.3.4", null);

        ArgumentCaptor<Inquiry> saved = ArgumentCaptor.forClass(Inquiry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getContactMethod()).isEqualTo(ContactMethod.INSTAGRAM);
        assertThat(saved.getValue().getInstagram()).isEqualTo("cafe_peak");
        assertThat(saved.getValue().getPhone()).isNull();
        ArgumentCaptor<InquiryReceivedEvent> event = ArgumentCaptor.forClass(InquiryReceivedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().contactMethod()).isEqualTo(ContactMethod.INSTAGRAM);
        assertThat(event.getValue().contact()).isEqualTo("cafe_peak");
    }

    @Test
    @DisplayName("수정: 없는 문의는 INQUIRY_NOT_FOUND, 없는 프로젝트 연결은 B2B_PROJECT_NOT_FOUND")
    void updateNotFound() {
        given(repository.findById(9L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(9L, new UpdateInquiryRequest(InquiryStatus.WON, null, null)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INQUIRY_NOT_FOUND);

        given(repository.findById(1L)).willReturn(Optional.of(Inquiry.builder().status(InquiryStatus.NEW).build()));
        given(projects.existsById("bp_none")).willReturn(false);
        assertThatThrownBy(() -> service.update(1L, new UpdateInquiryRequest(null, null, "bp_none")))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.B2B_PROJECT_NOT_FOUND);
    }

    static CreateInquiryRequest request(String fax) {
        return new CreateInquiryRequest(InquiryType.FESTIVAL, "  고흥 유자축제 ", "담당자", null, "010-1234-5678", null,
                null, null, "2026-11", null, null, null, true, false,
                new CreateInquiryRequest.Utm("meta", null, null, "b2b_festival", null), "/b2b/", fax);
    }
}
