package com.caestro.server.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 고정 창 레이트리미터(#125) 검증. 실제 Redis 필요(docker-compose up -d redis / CI 서비스).
 * 한도 판정·키 독립성·TTL 자동 청소(누수 없는 카운터)를 확인한다.
 */
class RedisRateLimiterTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisRateLimiter limiter;

    private static final String KEY = "rl:test:42";
    private static final String OTHER_KEY = "rl:test:other";

    @BeforeAll
    static void beforeAll() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        limiter = new RedisRateLimiter(redisTemplate);
    }

    @AfterAll
    static void afterAll() {
        redisTemplate.delete(KEY);
        redisTemplate.delete(OTHER_KEY);
        connectionFactory.destroy();
    }

    @BeforeEach
    void clean() {
        redisTemplate.delete(KEY);
        redisTemplate.delete(OTHER_KEY);
    }

    @Test
    @DisplayName("한도까지 허용하고, 같은 창에서는 초과분부터 계속 거절한다")
    void allowsUpToLimit_thenRejects() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(KEY, 3, Duration.ofSeconds(10))).isTrue();
        }
        assertThat(limiter.tryAcquire(KEY, 3, Duration.ofSeconds(10))).isFalse();
        assertThat(limiter.tryAcquire(KEY, 3, Duration.ofSeconds(10))).isFalse();
    }

    @Test
    @DisplayName("카운터 키에 창 길이 TTL이 걸린다 — 청소가 공짜고 누수가 없다")
    void counterKey_hasWindowTtl() {
        limiter.tryAcquire(KEY, 3, Duration.ofSeconds(10));

        Long ttl = redisTemplate.getExpire(KEY);
        assertThat(ttl).isBetween(1L, 10L);
    }

    @Test
    @DisplayName("키(사용자·IP)가 다르면 한도가 독립적이다")
    void differentKeys_independentBudgets() {
        assertThat(limiter.tryAcquire(KEY, 1, Duration.ofSeconds(10))).isTrue();
        assertThat(limiter.tryAcquire(KEY, 1, Duration.ofSeconds(10))).isFalse();

        assertThat(limiter.tryAcquire(OTHER_KEY, 1, Duration.ofSeconds(10))).isTrue();
    }
}
