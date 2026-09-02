package com.caestro.server.domain.auth.oauth;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import java.security.Key;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
public class AppleOAuthProvider implements OAuthProvider {

    private static final String PROVIDER_NAME = "apple";
    // 테스트가 같은 값으로 토큰을 만들 수 있도록 패키지 공개
    static final String ISSUER = "https://appleid.apple.com";

    private final WebClient webClient;
    private final String bundleId;
    private final String jwksUri;

    // kid → 애플 공개키 캐시. 키 회전으로 모르는 kid가 올 때만 재페치한다 (volatile 통짜 교체)
    private volatile Map<String, Key> keysByKid = Map.of();

    public AppleOAuthProvider(
            WebClient.Builder webClientBuilder,
            @Value("${apple.bundle-id}") String bundleId,
            @Value("${apple.jwks-uri:https://appleid.apple.com/auth/keys}") String jwksUri) {
        this.webClient = webClientBuilder.build();
        this.bundleId = bundleId;
        this.jwksUri = jwksUri;
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    /**
     * 웹 콜백(인가코드) 방식은 애플에서 지원하지 않는다 — 우리 클라이언트는 전부 네이티브 SDK 경로이고,
     * 애플의 code 교환에는 .p8 서명 client secret이 필요해 필요 시 탈퇴 revoke 작업과 함께 도입한다.
     *
     * @throws CustomException INVALID_OAUTH_PROVIDER - 항상
     */
    @Override
    public OAuthProfile getProfile(String code) {
        throw new CustomException(ErrorCode.INVALID_OAUTH_PROVIDER);
    }

    /**
     * 모바일(iOS SDK) 로그인: identity token(애플이 서명한 JWT)을 로컬 검증해 프로필을 추출한다.
     * 애플에는 프로필 조회 API가 없어 토큰 검증이 유일한 방법이다 — 애플 JWKS 공개키로 RS256 서명을
     * 확인하고 iss/aud(번들 ID)/exp를 검증한 뒤 sub를 oauthId로 쓴다.
     * 이름·프로필 사진은 토큰에 없으므로(최초 1회 클라이언트에게만 제공) null로 둔다.
     *
     * @param identityToken iOS SDK가 발급한 애플 identity token
     * @return 소셜 프로필 (oauthId = 애플 sub, nickname/profileImage = null)
     * @throws CustomException OAUTH_LOGIN_FAILED - 검증 실패(서명/iss/aud/exp/kid 불일치)
     */
    @Override
    public OAuthProfile getProfileByToken(String identityToken) {
        try {
            Claims claims = Jwts.parser()
                    .keyLocator(this::locateKey)
                    .requireIssuer(ISSUER)
                    .requireAudience(bundleId)
                    .build()
                    .parseSignedClaims(identityToken)
                    .getPayload();
            return new OAuthProfile(claims.getSubject(), null, null);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.warn("애플 identity token 검증 실패", e);
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        }
    }

    /**
     * JWT 헤더의 kid로 검증용 공개키를 찾는다. 캐시 미스(애플 키 회전 직후)면 JWKS를 한 번 재페치한다.
     */
    private Key locateKey(Header header) {
        String kid = (header instanceof ProtectedHeader protectedHeader) ? protectedHeader.getKeyId() : null;
        Key key = keysByKid.get(kid);
        if (key == null) {
            refreshKeys();
            key = keysByKid.get(kid);
        }
        if (key == null) {
            log.warn("애플 JWKS에 없는 kid: {}", kid);
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        }
        return key;
    }

    /**
     * 애플 JWKS 엔드포인트에서 공개키 목록을 받아 kid 캐시를 통째로 교체한다.
     */
    private synchronized void refreshKeys() {
        String json = webClient.get()
                .uri(jwksUri)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        JwkSet jwkSet = Jwks.setParser().build().parse(json);

        Map<String, Key> next = new HashMap<>();
        for (Jwk<?> jwk : jwkSet.getKeys()) {
            next.put(jwk.getId(), jwk.toKey());
        }
        this.keysByKid = Map.copyOf(next);
    }
}
