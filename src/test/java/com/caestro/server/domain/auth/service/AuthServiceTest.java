package com.caestro.server.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.auth.dto.response.TokenResponse;
import com.caestro.server.domain.auth.oauth.OAuthProvider;
import com.caestro.server.domain.auth.oauth.OAuthProvider.OAuthProfile;
import com.caestro.server.domain.auth.oauth.OAuthProviderRegistry;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.jwt.JwtProvider;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private OAuthProviderRegistry oAuthProviderRegistry;

    @Mock
    private OAuthProvider oAuthProvider;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "refreshExpiration", 1209600L);
    }

    @Test
    @DisplayName("모바일 토큰 로그인: access token으로 프로필을 조회해 JWT를 발급한다 (code 교환 없음)")
    void socialLoginByToken_issuesTokens() {
        // given
        String accessToken = "kakao-sdk-access-token";
        OAuthProfile profile = new OAuthProfile("kakao-123", "닉네임", "img.png");
        User user = User.builder()
                .oauthProvider("kakao")
                .oauthId("kakao-123")
                .nickname("닉네임")
                .role(User.Role.USER)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);

        given(oAuthProviderRegistry.getProvider("kakao")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken(accessToken)).willReturn(profile);
        given(userRepository.findByOauthProviderAndOauthId("kakao", "kakao-123"))
                .willReturn(Optional.of(user));
        given(jwtProvider.generateAccessToken(1L, User.Role.USER)).willReturn("jwt-access");
        given(jwtProvider.generateRefreshToken(1L)).willReturn("jwt-refresh");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        TokenResponse response = authService.socialLoginByToken("kakao", accessToken);

        // then
        assertThat(response.accessToken()).isEqualTo("jwt-access");
        assertThat(response.refreshToken()).isEqualTo("jwt-refresh");
        // 토큰 방식은 code 교환(getProfile)을 거치지 않고 getProfileByToken만 사용한다
        verify(oAuthProvider).getProfileByToken(accessToken);
        verify(oAuthProvider, never()).getProfile(any());
        // 리프레시 토큰을 Redis에 저장한다
        verify(valueOperations).set(any(String.class), any(String.class), any(Duration.class));
    }

    @Test
    @DisplayName("모바일 토큰 로그인: 최초 로그인 시 신규 유저를 저장한다")
    void socialLoginByToken_firstLogin_createsUser() {
        // given
        String accessToken = "google-sdk-access-token";
        OAuthProfile profile = new OAuthProfile("google-999", "구글유저", "g.png");
        User saved = User.builder()
                .oauthProvider("google")
                .oauthId("google-999")
                .nickname("구글유저")
                .role(User.Role.USER)
                .build();
        ReflectionTestUtils.setField(saved, "id", 2L);

        given(oAuthProviderRegistry.getProvider("google")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken(accessToken)).willReturn(profile);
        given(userRepository.findByOauthProviderAndOauthId("google", "google-999"))
                .willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willReturn(saved);
        given(jwtProvider.generateAccessToken(anyLong(), any())).willReturn("jwt-access");
        given(jwtProvider.generateRefreshToken(anyLong())).willReturn("jwt-refresh");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        TokenResponse response = authService.socialLoginByToken("google", accessToken);

        // then
        assertThat(response.accessToken()).isEqualTo("jwt-access");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("게스트 로그인: 신규 디바이스면 익명 유저를 생성해 JWT를 발급한다")
    void guestLogin_newDevice_createsGuestUser() {
        // given
        String deviceId = "device-abc";
        User guest = User.builder().deviceId(deviceId).role(User.Role.USER).build();
        ReflectionTestUtils.setField(guest, "id", 10L);

        given(userRepository.findByDeviceId(deviceId)).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willReturn(guest);
        given(jwtProvider.generateAccessToken(10L, User.Role.USER)).willReturn("g-access");
        given(jwtProvider.generateRefreshToken(10L)).willReturn("g-refresh");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        TokenResponse response = authService.guestLogin(deviceId);

        // then
        assertThat(response.accessToken()).isEqualTo("g-access");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("게스트 로그인: 기존 디바이스면 새로 생성하지 않고 재사용한다")
    void guestLogin_existingDevice_reusesUser() {
        // given
        String deviceId = "device-xyz";
        User guest = User.builder().deviceId(deviceId).role(User.Role.USER).build();
        ReflectionTestUtils.setField(guest, "id", 11L);

        given(userRepository.findByDeviceId(deviceId)).willReturn(Optional.of(guest));
        given(jwtProvider.generateAccessToken(anyLong(), any())).willReturn("a");
        given(jwtProvider.generateRefreshToken(anyLong())).willReturn("r");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        authService.guestLogin(deviceId);

        // then
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("계정 연동(Case A): 신규 소셜 계정이면 게스트를 제자리 승격하고 userId를 유지한다")
    void socialLoginByToken_guestUpgrade_linksInPlace() {
        // given
        String token = "kakao-token";
        OAuthProfile profile = new OAuthProfile("kakao-500", "카카오", "img");
        User guest = User.builder().deviceId("dev").role(User.Role.USER).build(); // isGuest == true
        ReflectionTestUtils.setField(guest, "id", 20L);

        given(oAuthProviderRegistry.getProvider("kakao")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken(token)).willReturn(profile);
        given(userRepository.findByOauthProviderAndOauthId("kakao", "kakao-500")).willReturn(Optional.empty());
        given(userRepository.findById(20L)).willReturn(Optional.of(guest));
        given(userRepository.save(guest)).willReturn(guest);
        given(jwtProvider.generateAccessToken(20L, User.Role.USER)).willReturn("a");
        given(jwtProvider.generateRefreshToken(20L)).willReturn("r");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        authService.socialLoginByToken("kakao", token, 20L);

        // then: 게스트 행에 oauth가 붙고(승격) userId(20L)는 유지 → 데이터 이관
        assertThat(guest.isGuest()).isFalse();
        assertThat(guest.getOauthProvider()).isEqualTo("kakao");
        assertThat(guest.getOauthId()).isEqualTo("kakao-500");
        verify(userRepository).save(guest);
    }

    @Test
    @DisplayName("계정 연동(Case B): 이미 가입된 소셜 계정이면 기존 계정으로 로그인하고 게스트를 건드리지 않는다")
    void socialLoginByToken_existingSocialAccount_ignoresGuest() {
        // given
        String token = "kakao-token";
        OAuthProfile profile = new OAuthProfile("kakao-600", "카카오", "img");
        User existingSocial = User.builder().oauthProvider("kakao").oauthId("kakao-600").role(User.Role.USER).build();
        ReflectionTestUtils.setField(existingSocial, "id", 30L);

        given(oAuthProviderRegistry.getProvider("kakao")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken(token)).willReturn(profile);
        given(userRepository.findByOauthProviderAndOauthId("kakao", "kakao-600")).willReturn(Optional.of(existingSocial));
        given(jwtProvider.generateAccessToken(30L, User.Role.USER)).willReturn("a");
        given(jwtProvider.generateRefreshToken(30L)).willReturn("r");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when: 게스트(99L)를 함께 넘겨도 기존 소셜 계정이 우선
        authService.socialLoginByToken("kakao", token, 99L);

        // then: 게스트 조회·신규 저장 없이 기존 계정으로 로그인
        verify(userRepository, never()).findById(anyLong());
        verify(userRepository, never()).save(any(User.class));
    }
}
