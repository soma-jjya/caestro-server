package com.caestro.server.domain.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 애플 서버 API(#121 Phase B) — JDK HttpServer로 애플 토큰/리보크 엔드포인트를 흉내 내고,
 * 테스트가 만든 EC 키(.p8과 동일 형식)로 client secret이 애플 규격대로 서명되는지까지 실제 검증한다.
 */
class AppleTokenServiceTest {

    private static final String BUNDLE_ID = "com.test.peakpic";
    private static final String TEAM_ID = "TEAM123456";
    private static final String KEY_ID = "KEY1234567";

    private HttpServer apple;
    private KeyPair signKeys;   // 테스트가 만든 ".p8" 키 (P-256)
    private final AtomicReference<String> tokenRequestBody = new AtomicReference<>();
    private final AtomicReference<String> revokeRequestBody = new AtomicReference<>();
    private final AtomicInteger tokenStatus = new AtomicInteger(200);
    private final AtomicInteger revokeStatus = new AtomicInteger(200);
    private AppleTokenService service;

    @BeforeEach
    void startFakeApple() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));   // 애플 .p8과 같은 곡선
        signKeys = generator.generateKeyPair();

        apple = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        apple.createContext("/auth/token", ex -> {
            tokenRequestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, tokenStatus.get(), "{\"refresh_token\":\"apple-rt-1\",\"token_type\":\"bearer\"}");
        });
        apple.createContext("/auth/revoke", ex -> {
            revokeRequestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, revokeStatus.get(), "");
        });
        apple.start();

        String base = "http://127.0.0.1:" + apple.getAddress().getPort();
        service = new AppleTokenService(WebClient.builder(), BUNDLE_ID, TEAM_ID, KEY_ID,
                pem(signKeys.getPrivate()), base + "/auth/token", base + "/auth/revoke");
    }

    @AfterEach
    void stopFakeApple() {
        apple.stop(0);
    }

    @Test
    @DisplayName("교환 성공: authorization code를 보내고 refresh token을 받는다")
    void exchange_returnsRefreshToken() {
        Optional<String> result = service.exchangeRefreshToken("auth-code-1");

        assertThat(result).contains("apple-rt-1");
        Map<String, String> form = parseForm(tokenRequestBody.get());
        assertThat(form.get("client_id")).isEqualTo(BUNDLE_ID);
        assertThat(form.get("grant_type")).isEqualTo("authorization_code");
        assertThat(form.get("code")).isEqualTo("auth-code-1");
    }

    @Test
    @DisplayName("client secret은 .p8 키로 서명된 애플 규격 JWT다 (kid/iss/sub/aud)")
    void clientSecret_matchesAppleSpec() {
        service.exchangeRefreshToken("auth-code-1");

        String clientSecret = parseForm(tokenRequestBody.get()).get("client_secret");
        // 우리가 만든 공개키로 서명이 검증되면 = 그 .p8로 서명했다는 증명
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(signKeys.getPublic())
                .build()
                .parseSignedClaims(clientSecret);

        assertThat(jws.getHeader().getKeyId()).isEqualTo(KEY_ID);
        assertThat(jws.getPayload().getIssuer()).isEqualTo(TEAM_ID);
        assertThat(jws.getPayload().getSubject()).isEqualTo(BUNDLE_ID);
        assertThat(jws.getPayload().getAudience()).contains(AppleTokenService.CLIENT_SECRET_AUDIENCE);
    }

    @Test
    @DisplayName("교환 실패(애플 5xx)면 empty를 돌려준다 — 로그인을 막지 않는다")
    void exchange_serverError_returnsEmpty() {
        tokenStatus.set(500);

        assertThat(service.exchangeRefreshToken("auth-code-1")).isEmpty();
    }

    @Test
    @DisplayName("revoke 성공: refresh token을 보내고 true를 돌려준다")
    void revoke_success() {
        boolean result = service.revoke("apple-rt-1");

        assertThat(result).isTrue();
        Map<String, String> form = parseForm(revokeRequestBody.get());
        assertThat(form.get("token")).isEqualTo("apple-rt-1");
        assertThat(form.get("token_type_hint")).isEqualTo("refresh_token");
    }

    @Test
    @DisplayName("revoke 실패(애플 5xx)면 false를 돌려준다 — 탈퇴를 막지 않는다")
    void revoke_serverError_returnsFalse() {
        revokeStatus.set(500);

        assertThat(service.revoke("apple-rt-1")).isFalse();
    }

    @Test
    @DisplayName("미설정(team-id/key-id/private-key 없음)이면 HTTP 호출 없이 조용히 생략한다")
    void unconfigured_skipsQuietly() {
        AppleTokenService unconfigured = new AppleTokenService(WebClient.builder(), BUNDLE_ID,
                "", "", "", "http://127.0.0.1:1/auth/token", "http://127.0.0.1:1/auth/revoke");

        assertThat(unconfigured.exchangeRefreshToken("code")).isEmpty();
        assertThat(unconfigured.revoke("token")).isFalse();
        assertThat(tokenRequestBody.get()).isNull();
        assertThat(revokeRequestBody.get()).isNull();
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        // 실제 애플처럼 JSON 헤더를 줘야 WebClient가 본문을 디코딩한다
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            if (bytes.length > 0) {
                os.write(bytes);
            }
        }
    }

    /** PrivateKey를 .p8 파일과 동일한 PKCS#8 PEM 문자열로 만든다. */
    private static String pem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> params = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return params;
    }
}
