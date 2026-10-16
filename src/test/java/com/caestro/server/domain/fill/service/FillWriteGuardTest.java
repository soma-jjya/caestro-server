package com.caestro.server.domain.fill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 쓰기 양 제한(#150): 속도 한도(리미터)와 총량 상한(활성 세션 수)이 각각 429로 막고 kind별로 집계되는지. */
@ExtendWith(MockitoExtension.class)
class FillWriteGuardTest {

    @Mock private RedisRateLimiter rateLimiter;
    @Mock private FillSessionRepository sessionRepository;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC);
    private FillWriteGuard guard;

    @BeforeEach
    void setUp() {
        guard = new FillWriteGuard(rateLimiter, sessionRepository, registry, clock);
        ReflectionTestUtils.setField(guard, "createLimit", 10);
        ReflectionTestUtils.setField(guard, "createWindowSeconds", 3600);
        ReflectionTestUtils.setField(guard, "uploadLimit", 30);
        ReflectionTestUtils.setField(guard, "uploadWindowSeconds", 60);
        ReflectionTestUtils.setField(guard, "maxActiveSessions", 20);
    }

    @Test
    @DisplayName("한도 안이면 생성·업로드 모두 통과하고 아무것도 집계하지 않는다")
    void underLimits_pass() {
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
        when(sessionRepository.countByOwnerIdAndExpiresAtAfter(eq(1L), any())).thenReturn(19L);

        assertThatCode(() -> guard.checkCreate(1L)).doesNotThrowAnyException();
        assertThatCode(() -> guard.checkUpload(1L)).doesNotThrowAnyException();

        assertThat(registry.find(FillWriteGuard.METRIC).counters()).isEmpty();
    }

    @Test
    @DisplayName("생성 속도 초과: 429 TOO_MANY_REQUESTS, kind=create 집계, 세션 수는 세지 않는다")
    void createRate_exceeded() {
        when(rateLimiter.tryAcquire(startsWith("rl:fill:create:"), eq(10), any())).thenReturn(false);

        assertThatThrownBy(() -> guard.checkCreate(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

        verify(sessionRepository, never()).countByOwnerIdAndExpiresAtAfter(anyLong(), any());
        assertThat(registry.counter(FillWriteGuard.METRIC, "kind", "create").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("활성 세션 상한 도달: 속도는 남아도 FILL_TOO_MANY_SESSIONS, kind=session_cap 집계")
    void sessionCap_reached() {
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
        when(sessionRepository.countByOwnerIdAndExpiresAtAfter(1L, LocalDateTime.now(clock))).thenReturn(20L);

        assertThatThrownBy(() -> guard.checkCreate(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILL_TOO_MANY_SESSIONS);

        assertThat(registry.counter(FillWriteGuard.METRIC, "kind", "session_cap").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("업로드 속도 초과: 429, kind=upload 집계. 생성 예산과 키가 다르다")
    void uploadRate_exceeded_independentKey() {
        when(rateLimiter.tryAcquire(startsWith("rl:fill:upload:"), eq(30), any())).thenReturn(false);

        assertThatThrownBy(() -> guard.checkUpload(7L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

        verify(rateLimiter).tryAcquire(eq("rl:fill:upload:7"), eq(30), any());
        verify(rateLimiter, never()).tryAcquire(startsWith("rl:fill:create:"), anyInt(), any());
        assertThat(registry.counter(FillWriteGuard.METRIC, "kind", "upload").count()).isEqualTo(1.0);
    }
}
