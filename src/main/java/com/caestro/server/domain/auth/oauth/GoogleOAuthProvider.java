package com.caestro.server.domain.auth.oauth;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public GoogleOAuthProvider(
            WebClient.Builder webClientBuilder,
            @Value("${google.client-id}") String clientId,
            @Value("${google.client-secret}") String clientSecret,
            @Value("${google.redirect-uri}") String redirectUri) {
        this.webClient = webClientBuilder.build();
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

    @Override
    public OAuthProfile getProfileByToken(String accessToken) {
        // 모바일 SDK가 이미 access token을 발급받았으므로 code 교환을 생략하고 바로 사용자 정보를 조회한다
        GoogleUserResponse userResponse = requestUserInfo(accessToken);
        return toProfile(userResponse);
    }

    private GoogleTokenResponse requestToken(String code) {
        try {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "authorization_code");
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);
            formData.add("redirect_uri", redirectUri);
            formData.add("code", code);

            return webClient.post()
                    .uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(formData)
                    .retrieve()
                    .bodyToMono(GoogleTokenResponse.class)
                    .block();
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
            return webClient.get()
                    .uri(USER_INFO_URI)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .bodyToMono(GoogleUserResponse.class)
                    .block();
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
