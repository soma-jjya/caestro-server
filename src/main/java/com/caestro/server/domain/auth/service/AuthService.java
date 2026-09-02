package com.caestro.server.domain.auth.service;

import com.caestro.server.domain.auth.dto.response.TokenResponse;
import com.caestro.server.domain.auth.oauth.AppleTokenService;
import com.caestro.server.domain.auth.oauth.OAuthProvider;
import com.caestro.server.domain.auth.oauth.OAuthProvider.OAuthProfile;
import com.caestro.server.domain.auth.oauth.OAuthProviderRegistry;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.jwt.JwtProvider;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String REFRESH_KEY_PREFIX = "refresh:";

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final RedisTemplate<String, String> redisTemplate;
    private final OAuthProviderRegistry oAuthProviderRegistry;
    private final AppleTokenService appleTokenService;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;


    public TokenResponse socialLogin(String providerName, String code) {
        return socialLogin(providerName, code, null);
    }

    /**
     * 소셜 로그인(웹 콜백). 호출자가 게스트면 계정 연동을 처리한다.
     *
     * @param guestUserId 게스트 JWT로 접근한 경우의 userId (없으면 null)
     */
    public TokenResponse socialLogin(String providerName, String code, Long guestUserId) {
        OAuthProvider provider = oAuthProviderRegistry.getProvider(providerName);
        OAuthProfile profile = provider.getProfile(code);
        User user = findOrCreateOrUpgrade(profile, providerName, guestUserId);
        return generateTokens(user);
    }

    /**
     * 게스트(익명) 로그인.
     * deviceId로 익명 User를 조회하거나 없으면 생성해 JWT를 발급한다.
     * 게스트도 정식 유저와 동일하게 userId 기반 JWT를 받으므로, 세션 생성·TURN·촬영 등
     * 대부분의 기능을 로그인 없이 사용할 수 있다. (결제/구독만 정식 로그인 필요)
     *
     * @param deviceId 클라이언트가 생성·보관하는 디바이스 식별자
     * @return accessToken + refreshToken 쌍
     */
    public TokenResponse guestLogin(String deviceId) {
        User user = userRepository.findByDeviceId(deviceId)
                .orElseGet(() -> userRepository.save(User.builder()
                        .deviceId(deviceId)
                        .role(User.Role.USER)
                        .build()));
        return generateTokens(user);
    }

    public TokenResponse socialLoginByToken(String providerName, String accessToken) {
        return socialLoginByToken(providerName, accessToken, null, null);
    }

    public TokenResponse socialLoginByToken(String providerName, String accessToken, Long guestUserId) {
        return socialLoginByToken(providerName, accessToken, guestUserId, null);
    }

    /**
     * 모바일(네이티브 SDK) 소셜 로그인.
     * SDK가 발급받은 토큰으로 프로필을 조회해 JWT를 발급한다. (Android·iOS 공통)
     *
     * @param providerName      OAuth provider 이름 (kakao, google, apple)
     * @param accessToken       소셜 플랫폼 토큰 (카카오=access token, 구글=ID token, 애플=identity token)
     * @param guestUserId       게스트 JWT로 접근한 경우의 userId (없으면 null)
     * @param authorizationCode 애플 전용(선택) — 탈퇴 시 revoke에 쓸 refresh token 교환용 1회성 코드
     * @return accessToken + refreshToken 쌍
     */
    public TokenResponse socialLoginByToken(String providerName, String accessToken, Long guestUserId,
            String authorizationCode) {
        // 1. provider 조회
        OAuthProvider provider = oAuthProviderRegistry.getProvider(providerName);

        // 2. access token으로 소셜 프로필 조회 (code 교환 생략)
        OAuthProfile profile = provider.getProfileByToken(accessToken);

        // 3. 유저 조회/생성 또는 게스트 계정 연동
        User user = findOrCreateOrUpgrade(profile, providerName, guestUserId);

        // 4. 애플: 탈퇴 revoke용 refresh token 확보 — 교환 실패해도 로그인은 진행한다 (best-effort)
        if ("apple".equals(providerName) && authorizationCode != null && !authorizationCode.isBlank()) {
            appleTokenService.exchangeRefreshToken(authorizationCode)
                    .ifPresent(refreshToken -> {
                        user.updateAppleRefreshToken(refreshToken);
                        userRepository.save(user);
                    });
        }

        // 5. JWT 발급
        return generateTokens(user);
    }

    /**
     * 소셜 프로필로 유저를 조회/생성하되, 호출자가 게스트면 계정 연동(업그레이드)을 처리한다.
     *
     * - 해당 소셜 계정이 이미 존재하면 그 계정으로 로그인한다.
     * - 없고 호출자가 게스트면, 게스트 User를 제자리 승격해 userId를 유지한다.
     * - 없고 게스트도 아니면 신규 유저를 생성한다.
     */
    private User findOrCreateOrUpgrade(OAuthProfile profile, String provider, Long guestUserId) {
        // 이미 가입된 소셜 계정이면 그 계정으로 로그인
        Optional<User> existing = userRepository.findByOauthProviderAndOauthId(provider, profile.oauthId());
        if (existing.isPresent()) {
            return existing.get();
        }

        // 호출자가 게스트면 제자리 승격 (userId 유지 → 그동안의 데이터 이관)
        if (guestUserId != null) {
            Optional<User> guest = userRepository.findById(guestUserId);
            if (guest.isPresent() && guest.get().isGuest()) {
                User upgraded = guest.get();
                upgraded.linkOAuth(provider, profile.oauthId(), profile.nickname(), profile.profileImage());
                return userRepository.save(upgraded);
            }
        }

        // 그 외: 신규 유저 생성 (일반 최초 로그인)
        return userRepository.save(User.builder()
                .oauthProvider(provider)
                .oauthId(profile.oauthId())
                .nickname(profile.nickname())
                .profileImage(profile.profileImage())
                .role(User.Role.USER)
                .build());
    }

    private TokenResponse generateTokens(User user) {
        String accessToken = jwtProvider.generateAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtProvider.generateRefreshToken(user.getId());

        redisTemplate.opsForValue().set(
                REFRESH_KEY_PREFIX + user.getId(),
                refreshToken,
                Duration.ofSeconds(refreshExpiration)
        );

        return new TokenResponse(accessToken, refreshToken);
    }

    public TokenResponse refreshAccessToken(String refreshToken) {
        if (!jwtProvider.validateRefreshToken(refreshToken)) {
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        Long userId = jwtProvider.getUserIdFromRefreshToken(refreshToken);
        String storedToken = redisTemplate.opsForValue().get(REFRESH_KEY_PREFIX + userId);

        if (storedToken == null || !storedToken.equals(refreshToken)) {
            redisTemplate.delete(REFRESH_KEY_PREFIX + userId);
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        return generateTokens(user);
    }

    public void logout(Long userId) {
        redisTemplate.delete(REFRESH_KEY_PREFIX + userId);
    }
}
