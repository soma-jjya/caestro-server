package com.caestro.server.domain.signaling.service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 라이트 모드(로그인 없이 QR로 참여하는 촬영자) 참여 토큰을 관리한다.
 * 토큰은 특정 세션에 귀속되며, Redis에 `lite-token:{token}` → sessionCode 로 저장되어
 * 핸드셰이크 시점에 빠르게 세션을 조회하는 역인덱스로 사용된다.
 * 세션이 종료되면 토큰을 무효화(delete)하여 만료 정책(세션 종료 후 만료)을 구현한다.
 */
@Service
@RequiredArgsConstructor
public class LiteTokenService {

    private static final String KEY_PREFIX = "lite-token:";
    private static final long TTL_MINUTES = 10;

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 세션에 귀속된 라이트 모드 참여 토큰을 발급하고 Redis에 저장한다.
     *
     * @param sessionCode 토큰이 귀속될 세션 코드
     * @return 추측 불가능한 랜덤 토큰 문자열
     */
    public String issue(String sessionCode) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(KEY_PREFIX + token, sessionCode, TTL_MINUTES, TimeUnit.MINUTES);
        return token;
    }

    /**
     * 토큰으로 귀속된 세션 코드를 조회한다.
     * 토큰이 없거나(존재하지 않음) 세션 종료로 무효화되었으면 null을 반환한다.
     *
     * @param token 라이트 모드 참여 토큰
     * @return 세션 코드 (유효하지 않으면 null)
     */
    public String resolveSessionCode(String token) {
        if (token == null) {
            return null;
        }
        return redisTemplate.opsForValue().get(KEY_PREFIX + token);
    }

    /**
     * 활동 중인 토큰의 TTL을 갱신한다 (슬라이딩 만료).
     *
     * @param token 라이트 모드 참여 토큰
     */
    public void refresh(String token) {
        if (token == null) {
            return;
        }
        redisTemplate.expire(KEY_PREFIX + token, TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 토큰을 무효화한다 (세션 종료 시 호출).
     *
     * @param token 라이트 모드 참여 토큰
     */
    public void invalidate(String token) {
        if (token == null) {
            return;
        }
        redisTemplate.delete(KEY_PREFIX + token);
    }
}
