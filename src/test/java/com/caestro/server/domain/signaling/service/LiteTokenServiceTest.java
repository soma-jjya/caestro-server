package com.caestro.server.domain.signaling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class LiteTokenServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private LiteTokenService liteTokenService;

    private static final String SESSION_CODE = "a1b2c3d4";

    @BeforeEach
    void setUp() {
        // lenient: 조회/무효화 테스트에서는 opsForValue를 쓰지 않으므로 각 테스트에서 필요 시 stub
    }

    @Test
    @DisplayName("토큰을 발급하면 랜덤 토큰을 반환하고 Redis에 세션 코드와 함께 저장한다")
    void issue_storesAndReturnsToken() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        String token = liteTokenService.issue(SESSION_CODE);

        assertThat(token).isNotBlank();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), eq(SESSION_CODE), eq(10L), eq(TimeUnit.MINUTES));
        assertThat(keyCaptor.getValue()).isEqualTo("lite-token:" + token);
    }

    @Test
    @DisplayName("토큰으로 세션 코드를 조회한다")
    void resolveSessionCode_returnsSessionCode() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("lite-token:tok123")).willReturn(SESSION_CODE);

        assertThat(liteTokenService.resolveSessionCode("tok123")).isEqualTo(SESSION_CODE);
    }

    @Test
    @DisplayName("존재하지 않는 토큰은 null을 반환한다")
    void resolveSessionCode_unknownToken_returnsNull() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("lite-token:unknown")).willReturn(null);

        assertThat(liteTokenService.resolveSessionCode("unknown")).isNull();
    }

    @Test
    @DisplayName("null 토큰 조회는 Redis 접근 없이 null을 반환한다")
    void resolveSessionCode_nullToken_returnsNull() {
        assertThat(liteTokenService.resolveSessionCode(null)).isNull();
    }

    @Test
    @DisplayName("토큰을 무효화하면 Redis에서 삭제한다")
    void invalidate_deletesKey() {
        liteTokenService.invalidate("tok123");
        verify(redisTemplate).delete("lite-token:tok123");
    }
}
