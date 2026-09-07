package com.caestro.server.domain.auth.oauth;

import com.caestro.server.global.resilience.ExternalApiGuard;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 애플 서버 API(토큰 교환·revoke) 클라이언트.
 * 로그인 검증(AppleOAuthProvider)과 달리 우리가 애플을 "호출"하는 쪽이라 client secret이 필요한데,
 * 애플은 고정 문자열 대신 .p8 개인키로 매번 서명한 단명 JWT(ES256)를 요구한다.
 * 모든 호출은 best-effort — 실패가 로그인·탈퇴 흐름을 막지 않고, 서킷 open(#125) 시에는
 * 기다림 없이 즉시 생략으로 강등된다 (CallNotPermittedException도 기존 catch가 흡수).
 */
@Slf4j
@Component
public class AppleTokenService {

    // 테스트가 같은 값으로 client secret을 검증할 수 있도록 패키지 공개
    static final String CLIENT_SECRET_AUDIENCE = "https://appleid.apple.com";
    // 로그인·탈퇴 경로를 오래 붙잡지 않도록 짧게 제한
    private static final Duration API_TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;
    private final ExternalApiGuard externalApiGuard;
    private final String bundleId;
    private final String teamId;
    private final String keyId;
    private final String privateKeyPem;
    private final String tokenUri;
    private final String revokeUri;

    // .p8 파싱 결과 캐시 — 설정은 불변이므로 첫 사용 시 1회만 파싱
    private volatile PrivateKey cachedSigningKey;

    public AppleTokenService(
            WebClient.Builder webClientBuilder,
            ExternalApiGuard externalApiGuard,
            @Value("${apple.bundle-id}") String bundleId,
            @Value("${apple.team-id:}") String teamId,
            @Value("${apple.key-id:}") String keyId,
            @Value("${apple.private-key:}") String privateKeyPem,
            @Value("${apple.token-uri:https://appleid.apple.com/auth/token}") String tokenUri,
            @Value("${apple.revoke-uri:https://appleid.apple.com/auth/revoke}") String revokeUri) {
        this.webClient = webClientBuilder.build();
        this.externalApiGuard = externalApiGuard;
        this.bundleId = bundleId;
        this.teamId = teamId;
        this.keyId = keyId;
        this.privateKeyPem = privateKeyPem;
        this.tokenUri = tokenUri;
        this.revokeUri = revokeUri;
    }

    @PostConstruct
    void logIfUnconfigured() {
        // 미설정이어도 기동은 막지 않는다(#111 기동 실패 교훈) — 대신 명확히 남겨 조용한 미설정을 방지
        if (!isConfigured()) {
            log.info("애플 서버 API 미설정(apple.team-id/key-id/private-key) — 로그인은 동작하지만 탈퇴 revoke는 생략된다");
        }
    }

    /**
     * 애플 서버 API 호출(교환·revoke)에 필요한 3종(team-id/key-id/private-key) 설정 여부.
     */
    public boolean isConfigured() {
        return !teamId.isBlank() && !keyId.isBlank() && !privateKeyPem.isBlank();
    }

    /**
     * 로그인 시 받은 authorization code를 애플 refresh token으로 교환한다.
     * 이 토큰은 탈퇴 시 애플에 연결 해제(revoke)를 통보하기 위해서만 보관한다(앱스토어 심사 요건).
     * 실패가 로그인을 막으면 안 되므로 예외 대신 Optional.empty를 돌려준다.
     *
     * @param authorizationCode iOS SDK가 로그인 시 발급받은 1회성 코드 (유효 5분)
     * @return 애플 refresh token (미설정·교환 실패 시 empty)
     */
    public Optional<String> exchangeRefreshToken(String authorizationCode) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", bundleId);
            form.add("client_secret", clientSecret());
            form.add("grant_type", "authorization_code");
            form.add("code", authorizationCode);

            // 인가코드는 1회성 — 재시도 금지, 서킷브레이커만 (#125)
            AppleTokenResponse response = externalApiGuard.oneShot("apple", () -> webClient.post()
                    .uri(tokenUri)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(AppleTokenResponse.class)
                    .block(API_TIMEOUT));
            return Optional.ofNullable(response).map(AppleTokenResponse::refreshToken);
        } catch (Exception e) {
            log.warn("애플 refresh token 교환 실패 — 로그인은 계속 진행", e);
            return Optional.empty();
        }
    }

    /**
     * 탈퇴 시 애플에 로그인 연결 해제를 통보한다 (앱스토어 심사 요건).
     * 실패가 탈퇴를 막으면 안 되므로 예외 대신 boolean을 돌려준다.
     *
     * @param refreshToken 로그인 때 교환·보관해둔 애플 refresh token
     * @return 통보 성공 여부
     */
    public boolean revoke(String refreshToken) {
        if (!isConfigured()) {
            log.warn("애플 revoke 생략 — 서버 API 미설정");
            return false;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", bundleId);
            form.add("client_secret", clientSecret());
            form.add("token", refreshToken);
            form.add("token_type_hint", "refresh_token");

            externalApiGuard.oneShot("apple", () -> webClient.post()
                    .uri(revokeUri)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .toBodilessEntity()
                    .block(API_TIMEOUT));
            return true;
        } catch (Exception e) {
            log.warn("애플 토큰 revoke 실패 — 탈퇴는 계속 진행", e);
            return false;
        }
    }

    /**
     * 애플 규격의 client secret 생성 — .p8 키로 서명한 단명(5분) ES256 JWT.
     * iss=Team ID, sub=번들 ID, aud=애플, 헤더 kid=Key ID.
     */
    private String clientSecret() throws Exception {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(keyId).and()
                .issuer(teamId)
                .subject(bundleId)
                .audience().add(CLIENT_SECRET_AUDIENCE).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(signingKey(), Jwts.SIG.ES256)
                .compact();
    }

    /**
     * .p8(PKCS#8 PEM)을 EC 개인키로 파싱한다. 기동 시가 아니라 첫 사용 시 파싱해,
     * 설정이 없는 환경(dev/CI)에서도 컨텍스트 기동을 막지 않는다.
     */
    private PrivateKey signingKey() throws Exception {
        PrivateKey key = cachedSigningKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (cachedSigningKey == null) {
                String base64 = privateKeyPem
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
                byte[] der = Base64.getDecoder().decode(base64);
                cachedSigningKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
            }
            return cachedSigningKey;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AppleTokenResponse(
            @JsonProperty("refresh_token") String refreshToken
    ) {
    }
}
