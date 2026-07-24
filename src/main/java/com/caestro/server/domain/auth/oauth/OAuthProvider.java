package com.caestro.server.domain.auth.oauth;

public interface OAuthProvider {

    String getName();

    /**
     * 웹 방식: authorization code를 토큰으로 교환한 뒤 프로필을 조회한다.
     */
    OAuthProfile getProfile(String code);

    /**
     * 모바일(네이티브 SDK) 방식: SDK가 이미 발급받은 access token으로 바로 프로필을 조회한다.
     * code 교환 단계가 없어 redirect_uri에 의존하지 않으며, Android·iOS 공통으로 사용된다.
     *
     * @param accessToken 소셜 플랫폼 access token (모바일 SDK가 발급)
     * @return 소셜 프로필
     */
    OAuthProfile getProfileByToken(String accessToken);

    record OAuthProfile(String oauthId, String nickname, String profileImage) {
    }
}
