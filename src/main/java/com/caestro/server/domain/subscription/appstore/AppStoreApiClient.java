package com.caestro.server.domain.subscription.appstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * App Store Server API 호출 (contracts/subscription-app-store.md).
 *
 * <p>인증: App Store Connect 의 인앱 결제 키(.p8)로 매번 서명한 단명 JWT(ES256). 로그인용 애플 키(apple.private-key)와
 * 종류가 다른 키다(사용자 및 액세스 › 통합 › 인앱 결제). 키가 비어 있어도 서버는 뜨고, 확인 요청만 503 이다.
 *
 * <p>환경: 운영(production)에 먼저 묻고, 그 거래를 모르면 샌드박스(TestFlight·개발 빌드의 결제)에 묻는다 — Apple 이 권하는 순서.
 */
@Slf4j
@Component
public class AppStoreApiClient implements AppStoreServerApi {

    static final String AUDIENCE = "appstoreconnect-v1";
    private static final String PRODUCTION = "https://api.storekit.itunes.apple.com";
    private static final String SANDBOX = "https://api.storekit-sandbox.itunes.apple.com";

    private final String bundleId;
    private final String issuerId;
    private final String keyId;
    private final String privateKeyPem;
    private final String environment;
    private final String productPrefix;
    private final RestClient client;
    private final ObjectMapper objectMapper;

    private volatile PrivateKey cachedSigningKey;

    @Autowired
    public AppStoreApiClient(@Value("${apple.app-store.bundle-id:soma.project.caestro}") String bundleId,
                             @Value("${apple.app-store.issuer-id:}") String issuerId,
                             @Value("${apple.app-store.key-id:}") String keyId,
                             @Value("${apple.app-store.private-key:}") String privateKeyPem,
                             @Value("${apple.app-store.environment:auto}") String environment,
                             @Value("${apple.app-store.product-prefix:peakpic_pro_}") String productPrefix,
                             ObjectMapper objectMapper) {
        this(bundleId, issuerId, keyId, privateKeyPem, environment, productPrefix, buildClient(), objectMapper);
    }

    AppStoreApiClient(String bundleId, String issuerId, String keyId, String privateKeyPem, String environment,
                      String productPrefix, RestClient client, ObjectMapper objectMapper) {
        this.bundleId = bundleId;
        this.issuerId = issuerId;
        this.keyId = keyId;
        this.privateKeyPem = privateKeyPem;
        this.environment = environment;
        this.productPrefix = productPrefix;
        this.client = client;
        this.objectMapper = objectMapper;
        if (!configured()) {
            log.info("App Store verification disabled: apple.app-store.issuer-id/key-id/private-key are empty");
        }
    }

    @Override
    public boolean configured() {
        return !issuerId.isBlank() && !keyId.isBlank() && !privateKeyPem.isBlank();
    }

    @Override
    public Optional<AppStoreSubscription> getSubscription(String transactionId) {
        if (!"sandbox".equalsIgnoreCase(environment)) {
            Optional<AppStoreSubscription> production = query(PRODUCTION, transactionId);
            if (production.isPresent() || "production".equalsIgnoreCase(environment)) {
                return production;
            }
        }
        return query(SANDBOX, transactionId);
    }

    private Optional<AppStoreSubscription> query(String base, String transactionId) {
        try {
            // 템플릿 변수로 넣어 번호가 경로 밖으로 새지 않게 인코딩한다
            String body = client.get()
                    .uri(base + "/inApps/v1/subscriptions/{transactionId}", transactionId)
                    .header("Authorization", "Bearer " + bearer())
                    .retrieve()
                    .body(String.class);
            return AppStoreSubscription.from(objectMapper.readTree(body), productPrefix, objectMapper);
        } catch (RestClientResponseException e) {
            if (isUnknownTransaction(e.getStatusCode())) {
                log.info("App Store: unknown transaction at {} ({})", base, e.getStatusCode().value());
                return Optional.empty();
            }
            throw new AppStoreUnavailableException("subscriptions failed: " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new AppStoreUnavailableException("subscriptions failed", e);
        } catch (AppStoreUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AppStoreUnavailableException("subscriptions response unreadable", e);
        }
    }

    /** 잘못된 번호(400)·모르는 거래(404). 그 밖(401 키 문제·429·5xx)은 장애로 본다(503). */
    private static boolean isUnknownTransaction(HttpStatusCode status) {
        int code = status.value();
        return code == 400 || code == 404;
    }

    /** 한 시간 미만 유효한 ES256 JWT. https://developer.apple.com/documentation/appstoreserverapi/generating_json_web_tokens_for_api_requests */
    String bearer() {
        if (!configured()) {
            throw new AppStoreUnavailableException("apple.app-store.issuer-id/key-id/private-key are not set");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(keyId).type("JWT").and()
                .issuer(issuerId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .audience().add(AUDIENCE).and()
                .claim("bid", bundleId)
                .signWith(signingKey(), Jwts.SIG.ES256)
                .compact();
    }

    /** .p8(PKCS#8 PEM)을 EC 개인키로. 첫 사용 때 한 번 읽는다(설정이 없는 환경에서도 기동은 막지 않게). */
    private PrivateKey signingKey() {
        PrivateKey key = cachedSigningKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (cachedSigningKey == null) {
                try {
                    String base64 = privateKeyPem
                            .replace("-----BEGIN PRIVATE KEY-----", "")
                            .replace("-----END PRIVATE KEY-----", "")
                            .replaceAll("\\s", "");
                    byte[] der = Base64.getDecoder().decode(base64);
                    cachedSigningKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
                } catch (Exception e) {
                    throw new AppStoreUnavailableException("apple.app-store.private-key is not a .p8 key", e);
                }
            }
            return cachedSigningKey;
        }
    }

    private static RestClient buildClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }
}
