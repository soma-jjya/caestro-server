package com.caestro.server.domain.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.push.dto.request.RegisterPushTokenRequest;
import com.caestro.server.domain.push.dto.response.PushTokenResponse;
import com.caestro.server.domain.push.entity.PushToken;
import com.caestro.server.domain.push.enums.PushPlatform;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 기기 토큰 등록 규칙(#206): 새 토큰은 저장, 아는 토큰은 지금 계정으로 옮겨 되살림, 남의 토큰 해제는 404. */
@ExtendWith(MockitoExtension.class)
class PushTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");

    @Mock private PushTokenRepository tokenRepository;
    @Mock private UserRepository userRepository;

    private PushTokenService service;

    @BeforeEach
    void setUp() {
        service = new PushTokenService(tokenRepository, userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static User user(Long id) {
        User user = User.builder().deviceId("d-" + id).role(User.Role.USER).build();   // 게스트도 등록된다
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    @DisplayName("새 토큰은 내 계정에 저장된다")
    void register_new() {
        given(userRepository.findById(7L)).willReturn(Optional.of(user(7L)));
        given(tokenRepository.findByToken("tok")).willReturn(Optional.empty());
        given(tokenRepository.save(any(PushToken.class))).willAnswer(inv -> inv.getArgument(0));

        PushTokenResponse res = service.register(7L, new RegisterPushTokenRequest(PushPlatform.ANDROID, "tok"));

        assertThat(res.platform()).isEqualTo(PushPlatform.ANDROID);
        assertThat(res.lastSeenAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("다른 계정에 묶여 있던(그리고 꺼져 있던) 토큰을 다시 등록하면 내 계정으로 옮겨 오고 되살아난다")
    void register_existing_reassignsAndRevives() {
        PushToken existing = PushToken.builder().user(user(8L)).platform(PushPlatform.IOS).token("tok")
                .lastSeenAt(LocalDateTime.ofInstant(NOW.minusSeconds(86_400), ZoneOffset.UTC)).build();
        existing.disable(LocalDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC));
        given(userRepository.findById(7L)).willReturn(Optional.of(user(7L)));
        given(tokenRepository.findByToken("tok")).willReturn(Optional.of(existing));

        service.register(7L, new RegisterPushTokenRequest(PushPlatform.IOS, "tok"));

        assertThat(existing.getUser().getId()).isEqualTo(7L);
        assertThat(existing.isActive()).isTrue();
        assertThat(existing.getLastSeenAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(tokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("해제: 내 토큰이면 지우고, 남의 토큰이거나 없으면 404")
    void unregister() {
        PushToken mine = PushToken.builder().user(user(7L)).platform(PushPlatform.ANDROID).token("tok")
                .lastSeenAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)).build();
        given(tokenRepository.findByToken("tok")).willReturn(Optional.of(mine));
        service.unregister(7L, "tok");
        verify(tokenRepository).delete(mine);

        assertThatThrownBy(() -> service.unregister(8L, "tok"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.PUSH_TOKEN_NOT_FOUND);
    }
}
