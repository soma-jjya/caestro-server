package com.caestro.server.domain.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.caestro.server.domain.auth.oauth.OAuthProvider.OAuthProfile;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

@ExtendWith(MockitoExtension.class)
class GoogleOAuthProviderTest {

    @Mock
    private GoogleIdTokenVerifier idTokenVerifier;

    private GoogleOAuthProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GoogleOAuthProvider(WebClient.builder(), idTokenVerifier, "cid", "secret", "uri");
    }

    @Test
    @DisplayName("모바일 ID token 검증 성공 시 sub/name/picture로 프로필을 만든다")
    void getProfileByToken_valid_returnsProfile() throws Exception {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-sub-123");
        payload.set("name", "구글유저");
        payload.set("picture", "pic.png");
        GoogleIdToken idToken = mock(GoogleIdToken.class);
        given(idToken.getPayload()).willReturn(payload);
        given(idTokenVerifier.verify("valid-id-token")).willReturn(idToken);

        OAuthProfile profile = provider.getProfileByToken("valid-id-token");

        assertThat(profile.oauthId()).isEqualTo("google-sub-123");
        assertThat(profile.nickname()).isEqualTo("구글유저");
        assertThat(profile.profileImage()).isEqualTo("pic.png");
    }

    @Test
    @DisplayName("ID token 검증 실패(null 반환)면 502(OAUTH_LOGIN_FAILED)")
    void getProfileByToken_invalid_throws() throws Exception {
        given(idTokenVerifier.verify("bad-token")).willReturn(null);

        assertThatThrownBy(() -> provider.getProfileByToken("bad-token"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_LOGIN_FAILED);
    }
}
