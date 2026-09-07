package com.caestro.server.domain.auth.oauth;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.resilience.ExternalApiGuard;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@Component
public class GoogleOAuthProvider implements OAuthProvider {

    private static final String PROVIDER_NAME = "google";
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String USER_INFO_URI = "https://www.googleapis.com/oauth2/v2/userinfo";

    private final WebClient webClient;
    private final ExternalApiGuard externalApiGuard;
    private final GoogleIdTokenVerifier idTokenVerifier;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public GoogleOAuthProvider(
            WebClient.Builder webClientBuilder,
            ExternalApiGuard externalApiGuard,
            GoogleIdTokenVerifier idTokenVerifier,
            @Value("${google.client-id}") String clientId,
            @Value("${google.client-secret}") String clientSecret,
            @Value("${google.redirect-uri}") String redirectUri) {
        this.webClient = webClientBuilder.build();
        this.externalApiGuard = externalApiGuard;
        this.idTokenVerifier = idTokenVerifier;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public OAuthProfile getProfile(String code) {
        GoogleTokenResponse tokenResponse = requestToken(code);
        GoogleUserResponse userResponse = requestUserInfo(tokenResponse.accessToken());
        return toProfile(userResponse);
    }

    /**
     * 모바일(Google Sign-In)에서 전달한 ID token을 로컬 검증해 프로필을 추출한다.
     * 카카오와 달리 구글 모바일 SDK는 access token이 아니라 ID token(구글이 서명한 JWT)을 주므로,
     * userinfo 호출 대신 구글 공개키로 서명·aud·iss·exp를 검증한 뒤 클레임(sub/name/picture)을 사용한다.
     *
     * @param idTokenString 모바일 SDK가 발급한 구글 ID token
     * @return 소셜 프로필 (oauthId = 구글 sub)
     * @throws CustomException OAUTH_LOGIN_FAILED - 검증 실패(서명/aud/iss/exp 불일치 등)
     */
    @Override
    public OAuthProfile getProfileByToken(String idTokenString) {
        try {
            GoogleIdToken idToken = idTokenVerifier.verify(idTokenString);
            if (idToken == null) {
                log.warn("구글 ID token 검증 실패 (서명/aud/iss/exp 불일치)");
                throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
            }
            GoogleIdToken.Payload payload = idToken.getPayload();
            String sub = payload.getSubject();
            String name = (String) payload.get("name");
            String picture = (String) payload.get("picture");
            return new OAuthProfile(sub, name, picture);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.warn("구글 ID token 검증 중 오류", e);
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        }
    }

    private GoogleTokenResponse requestToken(String code) {
        try {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "authorization_code");
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);
            formData.add("redirect_uri", redirectUri);
            formData.add("code", code);

            // 인가코드 교환은 1회성 자원 소비 — 서킷브레이커만 적용, 재시도 금지 (#125)
            return externalApiGuard.oneShot(PROVIDER_NAME, () -> webClient.post()
                    .uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(formData)
                    .retrieve()
                    .bodyToMono(GoogleTokenResponse.class)
                    .block());
        } catch (CallNotPermittedException e) {
            log.warn("구글 토큰 요청 차단: 서킷 open (외부 장애 감지, 즉시 실패)");
            throw new CustomException(ErrorCode.OAUTH_TEMPORARILY_UNAVAILABLE);
        } catch (WebClientResponseException e) {
            log.warn("구글 토큰 요청 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        } catch (Exception e) {
            log.warn("구글 토큰 요청 실패", e);
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        }
    }

    private GoogleUserResponse requestUserInfo(String accessToken) {
        try {
            // 사용자 정보 조회는 멱등(GET) — 서킷브레이커 + 인프라 장애 1회 재시도 (#125)
            return externalApiGuard.idempotent(PROVIDER_NAME, () -> webClient.get()
                    .uri(USER_INFO_URI)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .bodyToMono(GoogleUserResponse.class)
                    .block());
        } catch (CallNotPermittedException e) {
            log.warn("구글 사용자 정보 요청 차단: 서킷 open (외부 장애 감지, 즉시 실패)");
            throw new CustomException(ErrorCode.OAUTH_TEMPORARILY_UNAVAILABLE);
        } catch (WebClientResponseException e) {
            log.warn("구글 사용자 정보 요청 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        } catch (Exception e) {
            log.warn("구글 사용자 정보 요청 실패", e);
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        }
    }

    private OAuthProfile toProfile(GoogleUserResponse userResponse) {
        if (userResponse == null) {
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        }

        return new OAuthProfile(
                userResponse.id(),
                userResponse.name(),
                userResponse.picture()
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoogleTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("scope") String scope,
            @JsonProperty("id_token") String idToken
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoogleUserResponse(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("picture") String picture
    ) {
    }
}
