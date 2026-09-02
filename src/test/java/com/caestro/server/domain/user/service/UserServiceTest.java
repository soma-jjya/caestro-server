package com.caestro.server.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.auth.oauth.AppleTokenService;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.Duration;
import java.time.LocalDateTime;
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
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AppleTokenService appleTokenService;

    @InjectMocks
    private UserService userService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(userService, "accessExpiration", 3600L);
    }

    @Test
    @DisplayName("애플 유저 탈퇴: 애플에 revoke를 통보하고 refresh token도 함께 파기한다 (심사 요건)")
    void withdraw_appleUser_revokes() {
        // given
        User user = User.builder().oauthProvider("apple").oauthId("apple-sub-1").role(User.Role.USER).build();
        user.updateAppleRefreshToken("apple-rt-1");
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(appleTokenService.revoke("apple-rt-1")).willReturn(true);

        // when
        userService.withdraw(1L);

        // then
        verify(appleTokenService).revoke("apple-rt-1");
        assertThat(user.isDeleted()).isTrue();
        assertThat(user.getAppleRefreshToken()).isNull();
    }

    @Test
    @DisplayName("애플 revoke가 실패해도 탈퇴는 완료된다 (best-effort)")
    void withdraw_revokeFailure_stillWithdraws() {
        // given
        User user = User.builder().oauthProvider("apple").oauthId("apple-sub-2").role(User.Role.USER).build();
        user.updateAppleRefreshToken("apple-rt-2");
        ReflectionTestUtils.setField(user, "id", 2L);
        given(userRepository.findById(2L)).willReturn(Optional.of(user));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(appleTokenService.revoke("apple-rt-2")).willReturn(false);

        // when
        userService.withdraw(2L);

        // then: revoke 실패와 무관하게 익명화·토큰 무효화는 진행
        assertThat(user.isDeleted()).isTrue();
        verify(redisTemplate).delete("refresh:2");
        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("애플이 아닌 유저 탈퇴: revoke를 호출하지 않는다")
    void withdraw_nonAppleUser_noRevoke() {
        // given
        User user = User.builder().oauthProvider("kakao").oauthId("kakao-1").role(User.Role.USER).build();
        ReflectionTestUtils.setField(user, "id", 3L);
        given(userRepository.findById(3L)).willReturn(Optional.of(user));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        userService.withdraw(3L);

        // then
        verify(appleTokenService, never()).revoke(anyString());
        assertThat(user.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("이미 탈퇴한 계정은 USER_NOT_FOUND — revoke도 다시 호출하지 않는다")
    void withdraw_alreadyDeleted_throws() {
        // given
        User user = User.builder().oauthProvider("apple").oauthId("apple-sub-9").role(User.Role.USER).build();
        user.withdraw(LocalDateTime.now());
        ReflectionTestUtils.setField(user, "id", 4L);
        given(userRepository.findById(4L)).willReturn(Optional.of(user));

        // when & then
        assertThatThrownBy(() -> userService.withdraw(4L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
        verify(appleTokenService, never()).revoke(anyString());
    }
}
