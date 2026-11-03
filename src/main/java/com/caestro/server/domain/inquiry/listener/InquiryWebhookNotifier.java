package com.caestro.server.domain.inquiry.listener;

import com.caestro.server.domain.inquiry.event.InquiryReceivedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

/**
 * 새 문의 → 팀 채널 웹훅 한 줄 (#216). 커밋 뒤(AFTER_COMMIT), 요청 스레드 밖(@Async)이라 접수 응답은 웹훅을 기다리지 않는다.
 * {@code inquiry.webhook-url} 이 비면 아무것도 하지 않는다. Slack 은 {@code text}, Discord 는 {@code content} 를 읽으므로 둘 다 싣는다.
 */
@Slf4j
@Component
public class InquiryWebhookNotifier {

    static final String METRIC_FAILED = "inquiry.webhook.failed";

    private final String webhookUrl;
    private final String adminUrl;
    private final RestClient client;
    private final MeterRegistry meterRegistry;

    @Autowired
    public InquiryWebhookNotifier(@Value("${inquiry.webhook-url:}") String webhookUrl,
                                  @Value("${inquiry.admin-url:}") String adminUrl,
                                  MeterRegistry meterRegistry) {
        this(webhookUrl, adminUrl, buildClient(), meterRegistry);
    }

    InquiryWebhookNotifier(String webhookUrl, String adminUrl, RestClient client, MeterRegistry meterRegistry) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.strip();
        this.adminUrl = adminUrl == null ? "" : adminUrl.strip();
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    // 웹훅 한 번에 몇 초 이상 매달리지 않게 짧은 타임아웃
    static RestClient buildClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    public boolean isEnabled() {
        return !webhookUrl.isEmpty();
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReceived(InquiryReceivedEvent event) {
        if (!isEnabled()) {
            return;
        }
        String text = render(event);
        try {
            client.post().uri(webhookUrl).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text, "content", text))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            // 접수는 이미 저장됐다. 웹훅은 편의라 지표와 로그만 남긴다(URL 은 비밀값이라 찍지 않는다)
            meterRegistry.counter(METRIC_FAILED).increment();
            log.warn("Inquiry webhook failed: id={} {}", event.id(), e.toString());
        }
    }

    /** 채널 한 줄: 유형·업체·담당자·연락처·소재, 그리고 관리자 링크. */
    String render(InquiryReceivedEvent event) {
        StringBuilder sb = new StringBuilder()
                .append("[B2B 문의 #").append(event.id()).append("] ")
                .append(event.type().name()).append(" · ").append(event.organization())
                .append(" · ").append(event.contactName()).append(" · ").append(event.phone());
        if (event.utmContent() != null) {
            sb.append(" · 소재 ").append(event.utmContent());
        }
        if (!adminUrl.isEmpty()) {
            sb.append("\n").append(adminUrl).append("/").append(event.id());
        }
        return sb.toString();
    }
}
