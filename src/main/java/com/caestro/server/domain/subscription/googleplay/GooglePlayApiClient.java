package com.caestro.server.domain.subscription.googleplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Google Play Developer API(androidpublisher v3) 호출.
 *
 * 서비스 계정 키(JSON)는 google.play.service-account-json 으로 받는다(원문 또는 base64).
 * 키가 비어 있어도 서버는 뜨고, 확인 요청만 GooglePlayUnavailableException(503)으로 답한다.
 * Play Console 에서 이 서비스 계정에 "재무 데이터 보기 · 주문 및 구독 관리" 권한을 줘야 한다.
 */
@Slf4j
@Component
public class GooglePlayApiClient implements GooglePlayPublisher {

    private static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher";
    private static final String BASE_URL = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/";

    private final String packageName;
    private final GoogleCredentials credentials;
    private final RestClient client;
    private final ObjectMapper objectMapper;

    @Autowired
    public GooglePlayApiClient(@Value("${google.play.package-name:soma.project.caestro}") String packageName,
                               @Value("${google.play.service-account-json:}") String serviceAccountJson,
                               ObjectMapper objectMapper) {
        this(packageName, loadCredentials(serviceAccountJson), buildClient(), objectMapper);
    }

    GooglePlayApiClient(String packageName, GoogleCredentials credentials, RestClient client, ObjectMapper objectMapper) {
        this.packageName = packageName;
        this.credentials = credentials;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean configured() {
        return credentials != null;
    }

    @Override
    public Optional<GooglePlaySubscription> getSubscription(String purchaseToken) {
        try {
            // 템플릿 변수로 넣어 토큰이 경로 밖으로 새지 않게 인코딩한다
            String body = client.get()
                    .uri(BASE_URL + "{package}/purchases/subscriptionsv2/tokens/{token}", packageName, purchaseToken)
                    .header("Authorization", "Bearer " + accessToken())
                    .retrieve()
                    .body(String.class);
            return Optional.of(GooglePlaySubscription.from(objectMapper.readTree(body)));
        } catch (RestClientResponseException e) {
            if (isUnknownToken(e.getStatusCode())) {
                log.info("Google Play: unknown purchase token ({}): {}", e.getStatusCode().value(), shorten(e.getResponseBodyAsString()));
                return Optional.empty();
            }
            throw new GooglePlayUnavailableException("subscriptionsv2.get failed: " + e.getStatusCode().value(), e);
        } catch (RestClientException | IOException e) {
            throw new GooglePlayUnavailableException("subscriptionsv2.get failed", e);
        }
    }

    @Override
    public void acknowledge(String productId, String purchaseToken) {
        try {
            client.post()
                    .uri(BASE_URL + "{package}/purchases/subscriptions/{product}/tokens/{token}:acknowledge",
                            packageName, productId, purchaseToken)
                    .header("Authorization", "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // 앱이 먼저 승인했으면 400 이 온다. 권한 판단에는 영향이 없으니 기록만 한다
            log.info("Google Play acknowledge skipped ({}): {}", e.getStatusCode().value(), shorten(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            log.warn("Google Play acknowledge failed: {}", e.getMessage());
        }
    }

    private String accessToken() {
        if (credentials == null) {
            throw new GooglePlayUnavailableException("google.play.service-account-json is not set");
        }
        try {
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException e) {
            throw new GooglePlayUnavailableException("service account token refresh failed", e);
        }
    }

    /** 잘못된·만료돼 지워진·다른 앱의 토큰: Google 은 400/404/410 으로 답한다. */
    private static boolean isUnknownToken(HttpStatusCode status) {
        int code = status.value();
        return code == 400 || code == 404 || code == 410;
    }

    static GoogleCredentials loadCredentials(String serviceAccountJson) {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            log.info("Google Play verification disabled: google.play.service-account-json is empty");
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
            // 키가 잘못돼도 서버 전체가 뜨지 못하면 안 된다: 확인 기능만 꺼진다(503)
            log.error("Google Play verification disabled: google.play.service-account-json is not a service account key", e);
            return null;
        }
    }

    private static RestClient buildClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    private static String shorten(String body) {
        return body == null ? "" : body.length() > 300 ? body.substring(0, 300) : body;
    }
}
