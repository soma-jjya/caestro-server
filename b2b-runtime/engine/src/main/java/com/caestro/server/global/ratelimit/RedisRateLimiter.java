package com.caestro.server.global.ratelimit;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 고정 창 레이트리미터 (#125). 키 단위(사용자·IP)로 창 내 호출 수를 제한한다.
 * resilience4j RateLimiter(JVM 로컬, 자원 총량 보호기)가 아니라 Redis를 택한 이유:
 * 어뷰즈 방어는 키별 한도가 본질인데 로컬 리미터는 키마다 인스턴스가 쌓여 청소가 필요하고
 * 서버 대수만큼 실효 한도가 늘어난다 — Redis INCR은 원자적이고, 다중 인스턴스에서 정확하며,
 * TTL이 청소를 대신한다. 비용은 호출당 왕복 1회(이미 Redis 의존 경로들이라 무시 가능).
 */
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    // INCR+EXPIRE 원자 실행 (#73 클레임 스크립트와 같은 로딩 방식)
    private static final RedisScript<Long> RATE_LIMIT_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/rate_limit.lua"), Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 현재 창에서 호출 1회를 집계하고 한도 이내인지 판정한다.
     *
     * @param key    제한 단위 키 (예: "rl:join:{userId}", "rl:guest:{ip}")
     * @param limit  창 내 허용 호출 수
     * @param window 창 길이
     * @return 허용이면 true, 한도 초과면 false
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        Long count = redisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(key), String.valueOf(window.toSeconds()));
        return count != null && count <= limit;
    }
}
