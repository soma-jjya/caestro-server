package com.caestro.server.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.auth.dto.response.TokenResponse;
import com.caestro.server.domain.auth.oauth.AppleTokenService;
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

    @Mock
    private AppleTokenService appleTokenService;

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
        // nickname을 채워둔다 — 비어 있으면 fill-if-null(#134)이 save를 유발해 이 테스트의 관심사(게스트 불간섭)와 섞인다
        User existingSocial = User.builder().oauthProvider("kakao").oauthId("kakao-600")
                .nickname("기존닉").role(User.Role.USER).build();
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

    @Test
    @DisplayName("애플 로그인: authorizationCode가 오면 refresh token으로 교환해 유저에 저장한다 (탈퇴 revoke 대비)")
    void socialLoginByToken_apple_storesRefreshToken() {
        // given
        String identityToken = "apple-identity-token";
        OAuthProfile profile = new OAuthProfile("apple-sub-1", null, null);
        User user = User.builder().oauthProvider("apple").oauthId("apple-sub-1").role(User.Role.USER).build();
        ReflectionTestUtils.setField(user, "id", 40L);

        given(oAuthProviderRegistry.getProvider("apple")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken(identityToken)).willReturn(profile);
        given(userRepository.findByOauthProviderAndOauthId("apple", "apple-sub-1")).willReturn(Optional.of(user));
        given(appleTokenService.exchangeRefreshToken("auth-code-1")).willReturn(Optional.of("apple-rt-9"));
        given(userRepository.save(user)).willReturn(user);
        given(jwtProvider.generateAccessToken(40L, User.Role.USER)).willReturn("a");
        given(jwtProvider.generateRefreshToken(40L)).willReturn("r");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        authService.socialLoginByToken("apple", identityToken, null, "auth-code-1", null);

        // then: 탈퇴 시 revoke에 쓸 애플 refresh token이 유저에 저장된다
        assertThat(user.getAppleRefreshToken()).isEqualTo("apple-rt-9");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("애플 로그인: refresh token 교환이 실패해도 로그인은 성공한다 (best-effort)")
    void socialLoginByToken_apple_exchangeFailure_stillLogsIn() {
        // given
        String identityToken = "apple-identity-token";
        OAuthProfile profile = new OAuthProfile("apple-sub-2", null, null);
        User user = User.builder().oauthProvider("apple").oauthId("apple-sub-2").role(User.Role.USER).build();
        ReflectionTestUtils.setField(user, "id", 41L);

        given(oAuthProviderRegistry.getProvider("apple")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken(identityToken)).willReturn(profile);
        given(userRepository.findByOauthProviderAndOauthId("apple", "apple-sub-2")).willReturn(Optional.of(user));
        given(appleTokenService.exchangeRefreshToken("auth-code-2")).willReturn(Optional.empty());
        given(jwtProvider.generateAccessToken(41L, User.Role.USER)).willReturn("a");
        given(jwtProvider.generateRefreshToken(41L)).willReturn("r");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        TokenResponse response = authService.socialLoginByToken("apple", identityToken, null, "auth-code-2", null);

        // then: 교환 실패는 로그인 결과에 영향 없음, 토큰 저장도 없음
        assertThat(response.accessToken()).isEqualTo("a");
        assertThat(user.getAppleRefreshToken()).isNull();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("애플 신규 가입: 클라이언트 전달 nickname이 저장된다 (#134 — 애플은 이름을 앱에만 제공)")
    void appleSignup_clientNickname_saved() {
        OAuthProfile profile = new OAuthProfile("apple-sub-3", null, null); // 애플 프로필엔 이름 없음
        given(oAuthProviderRegistry.getProvider("apple")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken("t")).willReturn(profile);
        given(userRepository.findByOauthProviderAndOauthId("apple", "apple-sub-3")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(jwtProvider.generateAccessToken(any(), any())).willReturn("a");
        given(jwtProvider.generateRefreshToken(any())).willReturn("r");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        authService.socialLoginByToken("apple", "t", null, null, "진동현");

        org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("진동현");
    }

    @Test
    @DisplayName("기존 애플 가입자(nickname=null) 재로그인: 늦게 온 이름으로 채워진다 (기존 사용자 구제)")
    void appleRelogin_nullNickname_filled() {
        User existing = User.builder().oauthProvider("apple").oauthId("apple-sub-4").role(User.Role.USER).build();
        ReflectionTestUtils.setField(existing, "id", 50L);
        given(oAuthProviderRegistry.getProvider("apple")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken("t")).willReturn(new OAuthProfile("apple-sub-4", null, null));
        given(userRepository.findByOauthProviderAndOauthId("apple", "apple-sub-4")).willReturn(Optional.of(existing));
        given(userRepository.save(existing)).willReturn(existing);
        given(jwtProvider.generateAccessToken(50L, User.Role.USER)).willReturn("a");
        given(jwtProvider.generateRefreshToken(50L)).willReturn("r");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        authService.socialLoginByToken("apple", "t", null, null, "진동현");

        assertThat(existing.getNickname()).isEqualTo("진동현");
        verify(userRepository).save(existing);
    }

    @Test
    @DisplayName("nickname이 이미 있는 사용자: 덮어쓰지 않고 저장도 없다 (재로그인 시 null·다른 값 모두)")
    void appleRelogin_existingNickname_neverOverwritten() {
        User existing = User.builder().oauthProvider("apple").oauthId("apple-sub-5")
                .nickname("원래이름").role(User.Role.USER).build();
        ReflectionTestUtils.setField(existing, "id", 51L);
        given(oAuthProviderRegistry.getProvider("apple")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken("t")).willReturn(new OAuthProfile("apple-sub-5", null, null));
        given(userRepository.findByOauthProviderAndOauthId("apple", "apple-sub-5")).willReturn(Optional.of(existing));
        given(jwtProvider.generateAccessToken(51L, User.Role.USER)).willReturn("a");
        given(jwtProvider.generateRefreshToken(51L)).willReturn("r");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        authService.socialLoginByToken("apple", "t", null, null, "다른이름");

        assertThat(existing.getNickname()).isEqualTo("원래이름");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("카카오 요청에 nickname이 실려 와도 provider 프로필 이름이 우선한다 (필드 오남용 무해화)")
    void kakaoLogin_clientNickname_ignoredWhenProfileHasName() {
        given(oAuthProviderRegistry.getProvider("kakao")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken("t")).willReturn(new OAuthProfile("kakao-700", "카카오이름", "img"));
        given(userRepository.findByOauthProviderAndOauthId("kakao", "kakao-700")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(jwtProvider.generateAccessToken(any(), any())).willReturn("a");
        given(jwtProvider.generateRefreshToken(any())).willReturn("r");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        authService.socialLoginByToken("kakao", "t", null, null, "위조시도");

        org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("카카오이름");
    }

    @Test
    @DisplayName("공백-only nickname은 null 취급 — '이름 있음'으로 저장돼 이후 채움을 막지 않는다")
    void blankNickname_treatedAsNull() {
        given(oAuthProviderRegistry.getProvider("apple")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken("t")).willReturn(new OAuthProfile("apple-sub-6", null, null));
        given(userRepository.findByOauthProviderAndOauthId("apple", "apple-sub-6")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(jwtProvider.generateAccessToken(any(), any())).willReturn("a");
        given(jwtProvider.generateRefreshToken(any())).willReturn("r");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        authService.socialLoginByToken("apple", "t", null, null, "   ");

        org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getNickname()).isNull();
    }

    @Test
    @DisplayName("게스트 → 애플 승격 시에도 클라이언트 nickname이 채워진다 (linkOAuth 경로)")
    void guestUpgradeToApple_clientNickname_applied() {
        User guest = User.builder().deviceId("dev-a").role(User.Role.USER).build();
        ReflectionTestUtils.setField(guest, "id", 60L);
        given(oAuthProviderRegistry.getProvider("apple")).willReturn(oAuthProvider);
        given(oAuthProvider.getProfileByToken("t")).willReturn(new OAuthProfile("apple-sub-7", null, null));
        given(userRepository.findByOauthProviderAndOauthId("apple", "apple-sub-7")).willReturn(Optional.empty());
        given(userRepository.findById(60L)).willReturn(Optional.of(guest));
        given(userRepository.save(guest)).willReturn(guest);
        given(jwtProvider.generateAccessToken(60L, User.Role.USER)).willReturn("a");
        given(jwtProvider.generateRefreshToken(60L)).willReturn("r");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        authService.socialLoginByToken("apple", "t", 60L, null, "진동현");

        assertThat(guest.getNickname()).isEqualTo("진동현");
        assertThat(guest.getOauthProvider()).isEqualTo("apple");
    }
}
