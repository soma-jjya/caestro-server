package com.caestro.server.domain.push.sender;

import com.caestro.server.global.resilience.ExternalApiGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * FCM HTTP v1 로 보낸다 (#206). Firebase SDK 없이 Google Play 결제 확인과 같은 방식 — 서비스 계정 키로 액세스 토큰을 받아
 * REST 를 직접 부른다. iOS 도 FCM 이 APNs 로 넘기므로 서버는 FCM 하나만 상대한다.
 * 호출은 서킷브레이커 뒤에 있다. 4xx(없는 토큰·잘못된 요청)는 FCM 장애가 아니라 서킷 표본에서 빼고, 5xx·네트워크만 센다.
 * 생성자는 하나뿐이어야 한다 — 조건부 빈의 생성자 모호성으로 운영 기동이 실패한 전례(#111).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "push.enabled", havingValue = "true")
public class FcmPushSender implements PushSender {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    static final String CIRCUIT = "fcm";
    static final String BASE_URL = "https://fcm.googleapis.com/v1/projects/";

    private final String projectId;
    private final GoogleCredentials credentials;
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final ExternalApiGuard guard;

    @Autowired
    public FcmPushSender(@Value("${push.fcm.project-id:}") String projectId,
                         @Value("${push.fcm.service-account-json:}") String serviceAccountJson,
                         ObjectMapper objectMapper,
                         ExternalApiGuard guard) {
        this(projectId, loadCredentials(serviceAccountJson), buildClient(BASE_URL), objectMapper, guard);
    }

    FcmPushSender(String projectId, GoogleCredentials credentials, RestClient client, ObjectMapper objectMapper,
                  ExternalApiGuard guard) {
        this.projectId = projectId == null ? "" : projectId.trim();
        this.credentials = credentials;
        this.client = client;
        this.objectMapper = objectMapper;
        this.guard = guard;
    }

    @Override
    public boolean configured() {
        return credentials != null && !projectId.isBlank();
    }

    @Override
    public PushResult send(String token, PushMessage message) {
        if (!configured()) {
            log.warn("FCM not configured (push.fcm.project-id / service-account-json); push dropped: type={}", message.type());
            return PushResult.FAILED;
        }
        String body;
        try {
            body = objectMapper.writeValueAsString(payload(token, message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        try {
            return guard.noRetry(CIRCUIT, () -> {
                try {
                    client.post()
                            .uri("{project}/messages:send", projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .toBodilessEntity();
                    return PushResult.SENT;
                } catch (RestClientResponseException e) {
                    if (e.getStatusCode().is4xxClientError()) {
                        // 없는 토큰·잘못된 요청·권한: FCM 장애가 아니다 — 서킷 표본에서 뺀다
                        if (isUnregistered(e)) {
                            log.info("FCM token unregistered ({}): {}", e.getStatusCode().value(), shorten(e.getResponseBodyAsString()));
                            return PushResult.UNREGISTERED;
                        }
                        log.warn("FCM rejected the request ({}): {}", e.getStatusCode().value(), shorten(e.getResponseBodyAsString()));
                        return PushResult.FAILED;
                    }
                    throw e;   // 5xx 는 서킷이 센다
                }
            });
        } catch (CallNotPermittedException e) {
            log.warn("FCM circuit open; push dropped: type={}", message.type());
            return PushResult.FAILED;
        } catch (RuntimeException e) {
            log.warn("FCM send failed: {}", e.toString());
            return PushResult.FAILED;
        }
    }

    /** FCM v1 메시지. 알림(제목·본문)과 data(type·code 등)를 함께 보내 앱이 탭 시 어디로 갈지 안다. */
    Map<String, Object> payload(String token, PushMessage message) {
        Map<String, String> data = new LinkedHashMap<>(message.data());
        data.put("type", message.type());
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("token", token);
        msg.put("notification", Map.of("title", message.title(), "body", message.body()));
        msg.put("data", data);
        msg.put("android", Map.of("priority", "HIGH"));
        msg.put("apns", Map.of("headers", Map.of("apns-priority", "10")));
        return Map.of("message", msg);
    }

    /** 404 UNREGISTERED(앱 삭제·재설치)와 400 "not a valid FCM registration token" 둘 다 "없는 기기"다. */
    static boolean isUnregistered(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return e.getStatusCode().value() == 404
                || body.contains("UNREGISTERED")
                || body.contains("not a valid FCM registration token");
    }

    private String accessToken() {
        try {
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException e) {
            throw new IllegalStateException("FCM service account token refresh failed", e);
        }
    }

    static GoogleCredentials loadCredentials(String serviceAccountJson) {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            log.info("FCM disabled: push.fcm.service-account-json is empty");
            return null;
        }
        try {
            String json = serviceAccountJson.trim();
            if (!json.startsWith("{")) {
                json = new String(Base64.getDecoder().decode(json), StandardCharsets.UTF_8);
            }
            return GoogleCredentials.fromStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
                    .createScoped(List.of(SCOPE));
        } catch (IOException | IllegalArgumentException e) {
            // 키가 잘못돼도 서버 전체가 뜨지 못하면 안 된다: 푸시만 꺼진다
            log.error("FCM disabled: push.fcm.service-account-json is not a service account key", e);
            return null;
        }
    }

    static RestClient buildClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private static String shorten(String body) {
        return body == null ? "" : body.replaceAll("\\s+", " ").substring(0, Math.min(body.length(), 200));
    }
}
