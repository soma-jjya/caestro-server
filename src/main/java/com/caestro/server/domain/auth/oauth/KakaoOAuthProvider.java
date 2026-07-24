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
public class KakaoOAuthProvider implements OAuthProvider {

    private static final String PROVIDER_NAME = "kakao";
    private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";

    private final WebClient webClient;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public KakaoOAuthProvider(
            WebClient.Builder webClientBuilder,
            @Value("${kakao.client-id}") String clientId,
            @Value("${kakao.client-secret}") String clientSecret,
            @Value("${kakao.redirect-uri}") String redirectUri) {
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
        KakaoTokenResponse tokenResponse = requestToken(code);
        KakaoUserResponse userResponse = requestUserInfo(tokenResponse.accessToken());
        return toProfile(userResponse);
    }

    @Override
    public OAuthProfile getProfileByToken(String accessToken) {
        // 모바일 SDK가 이미 access token을 발급받았으므로 code 교환을 생략하고 바로 사용자 정보를 조회한다
        KakaoUserResponse userResponse = requestUserInfo(accessToken);
        return toProfile(userResponse);
    }

    private KakaoTokenResponse requestToken(String code) {
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
                    .bodyToMono(KakaoTokenResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("카카오 토큰 요청 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        } catch (Exception e) {
            log.warn("카카오 토큰 요청 실패", e);
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        }
    }

    private KakaoUserResponse requestUserInfo(String accessToken) {
        try {
            return webClient.get()
                    .uri(USER_INFO_URI)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .bodyToMono(KakaoUserResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("카카오 사용자 정보 요청 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        } catch (Exception e) {
            log.warn("카카오 사용자 정보 요청 실패", e);
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        }
    }

    private OAuthProfile toProfile(KakaoUserResponse userResponse) {
        if (userResponse == null) {
            throw new CustomException(ErrorCode.OAUTH_LOGIN_FAILED);
        }
        KakaoUserResponse.KakaoAccount account = userResponse.kakaoAccount();
        KakaoUserResponse.KakaoAccount.Profile profile = account != null ? account.profile() : null;

        String nickname = profile != null ? profile.nickname() : null;
        String profileImage = profile != null ? profile.profileImageUrl() : null;

        return new OAuthProfile(String.valueOf(userResponse.id()), nickname, profileImage);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("scope") String scope,
            @JsonProperty("refresh_token_expires_in") Long refreshTokenExpiresIn
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoUserResponse(
            @JsonProperty("id") Long id,
            @JsonProperty("kakao_account") KakaoAccount kakaoAccount
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record KakaoAccount(
                @JsonProperty("profile") Profile profile
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            private record Profile(
                    @JsonProperty("nickname") String nickname,
                    @JsonProperty("profile_image_url") String profileImageUrl
            ) {
            }
        }
    }
}
