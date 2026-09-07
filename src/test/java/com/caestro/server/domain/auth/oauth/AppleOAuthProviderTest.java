package com.caestro.server.domain.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caestro.server.domain.auth.oauth.OAuthProvider.OAuthProfile;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 애플 로그인(#121) — JDK 내장 HttpServer로 애플 JWKS를 흉내 내고, 테스트가 만든 RSA 키로
 * identity token을 서명해 검증 경로 전체를 실제로 통과시킨다 (#111 가짜 IMDS 패턴).
 * 정상 / 위조 서명 / aud·iss 불일치 / 만료 / kid 회전 재페치 / 캐시 / 웹 콜백 미지원.
 */
class AppleOAuthProviderTest {

    private static final String BUNDLE_ID = "com.test.peakpic";
    private static final String KID = "apple-key-1";

    private HttpServer jwksServer;
    private KeyPair appleKeys;                                            // "애플"의 서명 키
    private final AtomicReference<String> jwksBody = new AtomicReference<>();
    private final AtomicInteger jwksFetchCount = new AtomicInteger();
    private AppleOAuthProvider provider;

    @BeforeEach
    void startFakeJwks() throws Exception {
        appleKeys = rsaKeyPair();
        jwksBody.set(jwksJson(Map.of(KID, (RSAPublicKey) appleKeys.getPublic())));

        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/auth/keys", ex -> {
            jwksFetchCount.incrementAndGet();
            byte[] bytes = jwksBody.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        jwksServer.start();

        provider = new AppleOAuthProvider(WebClient.builder(),
                new com.caestro.server.global.resilience.ExternalApiGuard(
                        io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults()),
                BUNDLE_ID,
                "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/auth/keys");
    }

    @AfterEach
    void stopFakeJwks() {
        jwksServer.stop(0);
    }

    @Test
    @DisplayName("정상 identity token이면 sub를 oauthId로 반환한다 (닉네임·사진은 애플이 안 주므로 null)")
    void validToken_returnsProfile() {
        String token = identityToken(KID, appleKeys.getPrivate(),
                AppleOAuthProvider.ISSUER, BUNDLE_ID, inTenMinutes());

        OAuthProfile profile = provider.getProfileByToken(token);

        assertThat(profile.oauthId()).isEqualTo("apple-sub-001");
        assertThat(profile.nickname()).isNull();
        assertThat(profile.profileImage()).isNull();
    }

    @Test
    @DisplayName("공개키는 kid로 캐시된다 — 두 번째 검증은 JWKS를 다시 부르지 않는다")
    void secondVerification_usesCachedKey() {
        String token = identityToken(KID, appleKeys.getPrivate(),
                AppleOAuthProvider.ISSUER, BUNDLE_ID, inTenMinutes());

        provider.getProfileByToken(token);
        provider.getProfileByToken(token);

        assertThat(jwksFetchCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("모르는 kid(애플 키 회전)면 JWKS를 재페치해 새 키로 검증한다")
    void rotatedKid_refetchesJwks() throws Exception {
        // key-1을 캐시에 올린 뒤, 애플이 key-2로 회전한 상황을 재현
        provider.getProfileByToken(identityToken(KID, appleKeys.getPrivate(),
                AppleOAuthProvider.ISSUER, BUNDLE_ID, inTenMinutes()));

        KeyPair rotated = rsaKeyPair();
        jwksBody.set(jwksJson(Map.of("apple-key-2", (RSAPublicKey) rotated.getPublic())));
        String token = identityToken("apple-key-2", rotated.getPrivate(),
                AppleOAuthProvider.ISSUER, BUNDLE_ID, inTenMinutes());

        OAuthProfile profile = provider.getProfileByToken(token);

        assertThat(profile.oauthId()).isEqualTo("apple-sub-001");
        assertThat(jwksFetchCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰(위조)은 거부한다")
    void forgedSignature_rejected() throws Exception {
        KeyPair attacker = rsaKeyPair();
        String token = identityToken(KID, attacker.getPrivate(),
                AppleOAuthProvider.ISSUER, BUNDLE_ID, inTenMinutes());

        assertLoginFails(token);
    }

    @Test
    @DisplayName("aud가 우리 번들 ID가 아니면(남의 앱 토큰) 거부한다")
    void wrongAudience_rejected() {
        String token = identityToken(KID, appleKeys.getPrivate(),
                AppleOAuthProvider.ISSUER, "com.other.app", inTenMinutes());

        assertLoginFails(token);
    }

    @Test
    @DisplayName("iss가 애플이 아니면 거부한다")
    void wrongIssuer_rejected() {
        String token = identityToken(KID, appleKeys.getPrivate(),
                "https://evil.example.com", BUNDLE_ID, inTenMinutes());

        assertLoginFails(token);
    }

    @Test
    @DisplayName("만료된 토큰은 거부한다")
    void expiredToken_rejected() {
        String token = identityToken(KID, appleKeys.getPrivate(),
                AppleOAuthProvider.ISSUER, BUNDLE_ID, new Date(System.currentTimeMillis() - 60_000));

        assertLoginFails(token);
    }

    @Test
    @DisplayName("재페치해도 JWKS에 없는 kid면 거부한다 (재페치는 1회만 시도)")
    void unknownKid_rejectedAfterOneRefetch() {
        String token = identityToken("ghost-kid", appleKeys.getPrivate(),
                AppleOAuthProvider.ISSUER, BUNDLE_ID, inTenMinutes());

        assertLoginFails(token);
        assertThat(jwksFetchCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("웹 콜백(인가코드) 경로는 지원하지 않는다 — 애플은 모바일 SDK 전용")
    void webCallback_unsupported() {
        assertThatThrownBy(() -> provider.getProfile("any-code"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_OAUTH_PROVIDER);
    }

    private void assertLoginFails(String token) {
        assertThatThrownBy(() -> provider.getProfileByToken(token))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_LOGIN_FAILED);
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** 애플이 발급하는 identity token을 흉내 낸다 — 서명 키·kid·클레임을 바꿔 각 실패 케이스를 만든다. */
    private static String identityToken(String kid, PrivateKey signingKey,
                                        String issuer, String audience, Date expiration) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject("apple-sub-001")
                .expiration(expiration)
                .signWith(signingKey, Jwts.SIG.RS256)
                .compact();
    }

    /** RSA 공개키들을 애플 JWKS 응답 형식({"keys":[...]})의 JSON으로 만든다. */
    private static String jwksJson(Map<String, RSAPublicKey> publicKeys) throws Exception {
        List<Map<String, Object>> keys = new ArrayList<>();
        for (Map.Entry<String, RSAPublicKey> entry : publicKeys.entrySet()) {
            keys.add(new HashMap<>(Jwks.builder().key(entry.getValue()).id(entry.getKey()).build()));
        }
        return new ObjectMapper().writeValueAsString(Map.of("keys", keys));
    }

    private static Date inTenMinutes() {
        return new Date(System.currentTimeMillis() + 600_000);
    }
}
