package com.caestro.server.domain.signaling.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.caestro.server.domain.signaling.entity.SessionInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * JOIN 참여자 슬롯 경합 검증 (#73). 실제 Redis 필요(docker-compose up -d redis / CI 서비스).
 * naive read-modify-write가 동시 JOIN을 둘 다 허용하는 결함을 재현(영구 문서화)하고,
 * Lua 원자 획득은 정확히 한쪽만 승자가 됨을 검증한다.
 */
class JoinSlotClaimScriptTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static DefaultRedisScript<String> claimScript;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String KEY = "session:TEST42";

    @BeforeAll
    static void beforeAll() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);

        claimScript = new DefaultRedisScript<>();
        claimScript.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("scripts/join_slot_claim.lua")));
        claimScript.setResultType(String.class);
    }

    @AfterAll
    static void afterAll() {
        redisTemplate.delete(KEY);
        connectionFactory.destroy();
    }

    @BeforeEach
    void seedWaitingSession() throws Exception {
        // owner=1L만 있고 참여자 슬롯이 빈 WAITING 세션
        SessionInfo info = new SessionInfo();
        info.setSessionCode("TEST42");
        info.setOwnerUserId(1L);
        info.setOwnerSocketId("owner-sock");
        info.setCurrentDirectorUserId(1L);
        info.setStatus("WAITING");
        redisTemplate.opsForValue().set(KEY, objectMapper.writeValueAsString(info), Duration.ofMinutes(10));
    }

    private SessionInfo currentSession() throws Exception {
        return objectMapper.readValue(redisTemplate.opsForValue().get(KEY), SessionInfo.class);
    }

    /** 두 작업을 배리어로 정렬해 동시에 실행하고 결과를 돌려준다. */
    private List<String> runConcurrently(Callable<String> first, Callable<String> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> f1 = pool.submit(first);
            Future<String> f2 = pool.submit(second);
            return List.of(f1.get(), f2.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("[결함 재현] naive GET→검사→SET은 동시 JOIN을 둘 다 성공시킨다")
    void naiveReadModifyWrite_allowsDoubleClaim() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);

        // 기존 joinSession과 같은 순서: 읽기 → 빈 슬롯 확인 → (동시 진입) → 쓰기
        List<String> results = runConcurrently(
                naiveJoinTask(barrier, 2L, "sock-A"),
                naiveJoinTask(barrier, 3L, "sock-B"));

        // 둘 다 "성공"했다고 판단한다 — 이것이 결함이다
        assertThat(results).containsExactlyInAnyOrder("CLAIMED", "CLAIMED");
        // 최종 상태는 마지막 쓰기가 이기고, 진 쪽은 성공 응답을 받은 고아가 된다
        Long finalUser = currentSession().getParticipantUserId();
        assertThat(finalUser).isIn(2L, 3L);
    }

    private Callable<String> naiveJoinTask(CyclicBarrier barrier, long userId, String socketId) {
        return () -> {
            SessionInfo info = currentSession();                       // 1. 읽기
            if (info.getParticipantUserId() != null) return "OCCUPIED"; // 2. 검사
            barrier.await();                                           //    (두 스레드가 검사까지 마치고 동시 진입)
            info.setParticipantUserId(userId);                         // 3. 쓰기
            info.setParticipantSocketId(socketId);
            info.setStatus("CONNECTED");
            redisTemplate.opsForValue().set(KEY, objectMapper.writeValueAsString(info), Duration.ofMinutes(10));
            return "CLAIMED";
        };
    }

    @Test
    @DisplayName("Lua 원자 획득은 동시 JOIN에서 정확히 한쪽만 승자가 된다")
    void luaClaim_allowsSingleWinner() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<String> claim2 = () -> { barrier.await(); return executeClaim(2L, "sock-A"); };
        Callable<String> claim3 = () -> { barrier.await(); return executeClaim(3L, "sock-B"); };
        List<String> results = runConcurrently(claim2, claim3);

        assertThat(results).containsExactlyInAnyOrder("CLAIMED", "OCCUPIED");

        // 최종 상태는 승자와 정확히 일치한다
        SessionInfo saved = currentSession();
        long winner = results.get(0).equals("CLAIMED") ? 2L : 3L;
        assertThat(saved.getParticipantUserId()).isEqualTo(winner);
        assertThat(saved.getStatus()).isEqualTo("CONNECTED");
    }

    @Test
    @DisplayName("Lua 스크립트 분기: 세션 없음 / 방장 재입장 / 참여자 재입장 / 점유됨")
    void luaClaim_branches() throws Exception {
        assertThat(executeClaimOnKey("session:NOPE", 2L, "s")).isEqualTo("NOT_FOUND");
        assertThat(executeClaim(1L, "s")).isEqualTo("TAKEOVER_OWNER");

        assertThat(executeClaim(2L, "sock-A")).isEqualTo("CLAIMED");
        assertThat(executeClaim(2L, "sock-A2")).isEqualTo("TAKEOVER_PARTICIPANT");
        assertThat(executeClaim(3L, "sock-B")).isEqualTo("OCCUPIED");

        // 스크립트를 거친 뒤에도 Jackson 역직렬화가 온전해야 한다 (cjson 재인코딩 호환)
        SessionInfo saved = currentSession();
        assertThat(saved.getOwnerUserId()).isEqualTo(1L);
        assertThat(saved.getParticipantUserId()).isEqualTo(2L);
        assertThat(saved.getParticipantSocketId()).isEqualTo("sock-A");
    }

    private String executeClaim(long userId, String socketId) {
        return executeClaimOnKey(KEY, userId, socketId);
    }

    private String executeClaimOnKey(String key, long userId, String socketId) {
        return redisTemplate.execute(claimScript, List.of(key),
                String.valueOf(userId), socketId, "600");
    }
}
