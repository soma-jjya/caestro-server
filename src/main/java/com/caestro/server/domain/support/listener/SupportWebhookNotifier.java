package com.caestro.server.domain.support.listener;

import com.caestro.server.domain.support.event.SupportMessageCreatedEvent;
import com.caestro.server.domain.support.event.SupportTicketCreatedEvent;
import io.micrometer.core.instrument.MeterRegistry;
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

import java.time.Duration;
import java.util.Map;

/**
 * 새 문의·이어 쓰기 → 팀 채널 웹훅 한 줄 (#263). B2B 문의(#216)와 같은 방식이고 URL 도 기본은 그것을 같이 쓴다.
 * 커밋 뒤(AFTER_COMMIT), 요청 스레드 밖(@Async). 글 내용·닉네임은 싣지 않고 분류·제목·기기와 관리자 링크만.
 */
@Slf4j
@Component
public class SupportWebhookNotifier {

    static final String METRIC_FAILED = "support.webhook.failed";

    private final String webhookUrl;
    private final String adminUrl;
    private final RestClient client;
    private final MeterRegistry meterRegistry;

    @Autowired
    public SupportWebhookNotifier(@Value("${support.webhook-url:}") String webhookUrl,
                                  @Value("${support.admin-url:}") String adminUrl,
                                  MeterRegistry meterRegistry) {
        this(webhookUrl, adminUrl, buildClient(), meterRegistry);
    }

    SupportWebhookNotifier(String webhookUrl, String adminUrl, RestClient client, MeterRegistry meterRegistry) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.strip();
        this.adminUrl = adminUrl == null ? "" : adminUrl.strip().replaceAll("/+$", "");
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

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
    public void onCreated(SupportTicketCreatedEvent event) {
        post(renderCreated(event), event.ticketId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessage(SupportMessageCreatedEvent event) {
        post(renderMessage(event), event.ticketId());
    }

    /** 예: {@code [앱 문의 #12] BUG · 사진이 안 올라가요 · ANDROID 1.4.2} + 관리자 링크 */
    String renderCreated(SupportTicketCreatedEvent event) {
        StringBuilder sb = new StringBuilder()
                .append("[앱 문의 #").append(event.ticketId()).append("] ")
                .append(event.category().name()).append(" · ").append(event.title());
        if (event.platform() != null || event.appVersion() != null) {
            sb.append(" · ").append(event.platform() == null ? "" : event.platform())
                    .append(event.platform() != null && event.appVersion() != null ? " " : "")
                    .append(event.appVersion() == null ? "" : event.appVersion());
        }
        return withLink(sb, event.ticketId());
    }

    /** 예: {@code [앱 문의 #12] 이어 쓰기 · 사진이 안 올라가요} + 관리자 링크 */
    String renderMessage(SupportMessageCreatedEvent event) {
        return withLink(new StringBuilder().append("[앱 문의 #").append(event.ticketId()).append("] 이어 쓰기 · ").append(event.title()),
                event.ticketId());
    }

    private String withLink(StringBuilder sb, Long ticketId) {
        if (!adminUrl.isEmpty()) {
            sb.append("\n").append(adminUrl).append("/").append(ticketId);
        }
        return sb.toString();
    }

    private void post(String text, Long ticketId) {
        if (!isEnabled()) {
            return;
        }
        try {
            client.post().uri(webhookUrl).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text, "content", text))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            // 문의는 이미 저장됐다. 웹훅은 편의라 지표와 로그만(URL 은 비밀값이라 찍지 않는다)
            meterRegistry.counter(METRIC_FAILED).increment();
            log.warn("Support webhook failed: ticket={} {}", ticketId, e.toString());
        }
    }
}
