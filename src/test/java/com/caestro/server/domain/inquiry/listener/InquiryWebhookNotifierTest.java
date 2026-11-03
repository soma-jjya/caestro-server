package com.caestro.server.domain.inquiry.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.caestro.server.domain.inquiry.enums.InquiryType;
import com.caestro.server.domain.inquiry.event.InquiryReceivedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 웹훅: URL 이 비면 아무 호출도 없고, 있으면 Slack·Discord 둘 다 읽는 모양으로 한 번, 실패는 지표만. */
class InquiryWebhookNotifierTest {

    static final InquiryReceivedEvent EVENT = new InquiryReceivedEvent(42L, InquiryType.CAFE, "디저트 카페", "김담당",
            "010-1234-5678", "b2b_cafe");

    @Test
    @DisplayName("URL 이 비어 있으면 꺼진 상태 — 호출 없음")
    void disabledWhenBlank() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        InquiryWebhookNotifier notifier = new InquiryWebhookNotifier(" ", "", builder.build(), new SimpleMeterRegistry());

        assertThat(notifier.isEnabled()).isFalse();
        notifier.onReceived(EVENT);
        server.verify();
    }

    @Test
    @DisplayName("요약 한 줄을 text·content 둘 다에 실어 POST 하고, 관리자 링크에 id 를 붙인다")
    void postsSummary() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://hooks.example/abc"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.text", Matchers.containsString("[B2B 문의 #42] CAFE · 디저트 카페 · 김담당 · 010-1234-5678 · 소재 b2b_cafe")))
                .andExpect(jsonPath("$.text", Matchers.containsString("https://peakpic.app/admin/inquiries/42")))
                .andExpect(jsonPath("$.content", Matchers.startsWith("[B2B 문의 #42]")))
                .andRespond(withSuccess());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        InquiryWebhookNotifier notifier = new InquiryWebhookNotifier("https://hooks.example/abc",
                "https://peakpic.app/admin/inquiries", builder.build(), meters);

        notifier.onReceived(EVENT);

        server.verify();
        assertThat(meters.find(InquiryWebhookNotifier.METRIC_FAILED).counter()).isNull();
    }

    @Test
    @DisplayName("웹훅이 5xx 를 주면 예외 없이 지표만 1")
    void failureIsCounted() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://hooks.example/abc")).andRespond(withServerError());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        InquiryWebhookNotifier notifier = new InquiryWebhookNotifier("https://hooks.example/abc", "", builder.build(), meters);

        notifier.onReceived(EVENT);

        server.verify();
        assertThat(meters.counter(InquiryWebhookNotifier.METRIC_FAILED).count()).isEqualTo(1.0);
    }
}
