package com.caestro.server.domain.signaling.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.caestro.server.domain.signaling.entity.SessionInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * relay 조회+수명연장 Lua 검증 (#98). 실제 Redis 필요(docker-compose up -d redis / CI 서비스).
 * 기존 5왕복(GET·GET·PEXPIRE×3)과 동일한 의미를 1왕복으로 재현하는지 분기별로 확인한다.
 */
class RelayTouchScriptTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static DefaultRedisScript<String> touchScript;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SESSION_CODE = "RLYT42";
    private static final String OWNER_SOCKET_KEY = "socket:relay-o1";
    private static final String PARTICIPANT_SOCKET_KEY = "socket:relay-p1";
    private static final String SESSION_KEY = "session:" + SESSION_CODE;
    private static final String TTL_MS = "600000"; // 10분 — 운영 상수와 동일

    @BeforeAll
    static void beforeAll() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);

        touchScript = new DefaultRedisScript<>();
        touchScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/relay_touch.lua")));
        touchScript.setResultType(String.class);
    }

    @AfterAll
    static void afterAll() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void cleanUp() {
        redisTemplate.delete(List.of(OWNER_SOCKET_KEY, PARTICIPANT_SOCKET_KEY, SESSION_KEY));
    }

    /** null 필드를 그대로 포함하는 실제 저장 형식과 동일한 세션 JSON을 만든다. */
    private String sessionJson(String ownerSocketId, String participantSocketId) throws Exception {
        SessionInfo info = new SessionInfo();
        info.setSessionCode(SESSION_CODE);
        info.setOwnerUserId(1L);
        info.setOwnerSocketId(ownerSocketId);
        info.setParticipantUserId(participantSocketId == null ? null : 2L);
        info.setParticipantSocketId(participantSocketId);
        info.setStatus(participantSocketId == null ? "WAITING" : "CONNECTED");
        return objectMapper.writeValueAsString(info);
    }

    @Test
    @DisplayName("정상 경로: 세션 JSON을 반환하고 방·소켓 3개의 수명을 모두 연장한다")
    void touch_returnsJsonAndRefreshesAllTtls() throws Exception {
        String json = sessionJson("relay-o1", "relay-p1");
        // 짧은 수명으로 심어두고, 스크립트 후 10분 근처로 늘었는지 본다
        redisTemplate.opsForValue().set(OWNER_SOCKET_KEY, SESSION_CODE, 5, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(SESSION_KEY, json, 5, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(PARTICIPANT_SOCKET_KEY, SESSION_CODE, 5, TimeUnit.SECONDS);

        String result = redisTemplate.execute(touchScript, List.of(OWNER_SOCKET_KEY), TTL_MS);

        assertThat(result).isEqualTo(json);
        assertThat(redisTemplate.getExpire(SESSION_KEY, TimeUnit.MILLISECONDS)).isGreaterThan(500_000L);
        assertThat(redisTemplate.getExpire(OWNER_SOCKET_KEY, TimeUnit.MILLISECONDS)).isGreaterThan(500_000L);
        assertThat(redisTemplate.getExpire(PARTICIPANT_SOCKET_KEY, TimeUnit.MILLISECONDS)).isGreaterThan(500_000L);
    }

    @Test
    @DisplayName("소켓이 어느 세션에도 속하지 않으면 null을 반환한다")
    void touch_unknownSocket_returnsNull() {
        String result = redisTemplate.execute(touchScript, List.of("socket:relay-ghost"), TTL_MS);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("매핑은 있으나 세션이 만료됐으면 null을 반환한다")
    void touch_expiredSession_returnsNull() {
        redisTemplate.opsForValue().set(OWNER_SOCKET_KEY, SESSION_CODE, 5, TimeUnit.SECONDS);
        // session:{code} 없음

        String result = redisTemplate.execute(touchScript, List.of(OWNER_SOCKET_KEY), TTL_MS);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("참여자 슬롯이 비어 있으면(JSON null 필드) 방·owner 소켓만 연장한다")
    void touch_waitingSession_refreshesOwnerOnly() throws Exception {
        String json = sessionJson("relay-o1", null); // participantSocketId = null 포함 JSON
        redisTemplate.opsForValue().set(OWNER_SOCKET_KEY, SESSION_CODE, 5, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(SESSION_KEY, json, 5, TimeUnit.SECONDS);

        String result = redisTemplate.execute(touchScript, List.of(OWNER_SOCKET_KEY), TTL_MS);

        assertThat(result).isEqualTo(json); // cjson.null 분기에서 오류 없이 통과
        assertThat(redisTemplate.getExpire(SESSION_KEY, TimeUnit.MILLISECONDS)).isGreaterThan(500_000L);
        assertThat(redisTemplate.getExpire(OWNER_SOCKET_KEY, TimeUnit.MILLISECONDS)).isGreaterThan(500_000L);
    }
}
