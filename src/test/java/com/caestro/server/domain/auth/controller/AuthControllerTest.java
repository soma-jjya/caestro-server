package com.caestro.server.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.auth.dto.request.GuestLoginRequest;
import com.caestro.server.domain.auth.dto.response.TokenResponse;
import com.caestro.server.domain.auth.service.AuthService;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 게스트 발급 레이트리밋(#125→#129) — 무인증 공개 + DB write 경로의 IP 단위 발급 제한과,
 * 오탐 관측용 429 카운터(공유 IP 눈금 조정의 실측 루프 재료)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RedisRateLimiter rateLimiter;

    @Mock
    private HttpServletRequest httpRequest;

    private SimpleMeterRegistry meterRegistry;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        controller = new AuthController(authService, userRepository, rateLimiter, meterRegistry);
        ReflectionTestUtils.setField(controller, "guestRateLimit", 30);
        ReflectionTestUtils.setField(controller, "guestRateWindowSeconds", 60);
    }

    @Test
    @DisplayName("한도 이내면 토큰을 발급하고 429 카운터는 남지 않는다")
    void guestLogin_withinLimit_issuesTokens() {
        given(httpRequest.getHeader("X-Forwarded-For")).willReturn(null);
        given(httpRequest.getRemoteAddr()).willReturn("1.2.3.4");
        given(rateLimiter.tryAcquire("rl:guest:1.2.3.4", 30, Duration.ofSeconds(60))).willReturn(true);
        given(authService.guestLogin("device-1")).willReturn(new TokenResponse("a", "r"));

        var response = controller.guestLogin(new GuestLoginRequest("device-1"), httpRequest);

        assertThat(response.getBody().accessToken()).isEqualTo("a");
        assertThat(meterRegistry.find("auth.guest.rate_limited").counter()).isNull();
    }

    @Test
    @DisplayName("한도 초과면 TOO_MANY_REQUESTS를 던지고 오탐 관측용 카운터를 남긴다 (#129)")
    void guestLogin_overLimit_countsAndRejects() {
        given(httpRequest.getHeader("X-Forwarded-For")).willReturn(null);
        given(httpRequest.getRemoteAddr()).willReturn("1.2.3.4");
        given(rateLimiter.tryAcquire(anyString(), anyInt(), any())).willReturn(false);

        assertThatThrownBy(() -> controller.guestLogin(new GuestLoginRequest("device-1"), httpRequest))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

        assertThat(meterRegistry.get("auth.guest.rate_limited").counter().count()).isEqualTo(1);
        verify(authService, never()).guestLogin(anyString());
    }

    @Test
    @DisplayName("ALB 뒤에서는 X-Forwarded-For의 첫 값이 제한 키다 — remoteAddr(ALB IP)로 전원이 묶이면 안 된다")
    void guestLogin_behindAlb_usesFirstForwardedIp() {
        given(httpRequest.getHeader("X-Forwarded-For")).willReturn("9.9.9.9, 10.0.0.1");
        given(rateLimiter.tryAcquire(eq("rl:guest:9.9.9.9"), anyInt(), any())).willReturn(true);
        given(authService.guestLogin("device-1")).willReturn(new TokenResponse("a", "r"));

        controller.guestLogin(new GuestLoginRequest("device-1"), httpRequest);

        verify(rateLimiter).tryAcquire(eq("rl:guest:9.9.9.9"), anyInt(), any());
    }
}
