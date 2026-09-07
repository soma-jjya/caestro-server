package com.caestro.server.domain.session.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.caestro.server.domain.session.entity.Session;
import com.caestro.server.domain.session.repository.SessionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * #124 핵심 증명 — 기록 3종(create/join/end)이 "어떤 순서로 도착해도" 최종 DB 행이 같다.
 * 멀티 인스턴스에서 create(방장 인스턴스)와 join/end(참여자 인스턴스)는 서로 다른 큐를 타므로
 * 도착 순서는 보장할 수 없고, 보장하는 대신 순서가 무의미하도록(멱등 upsert + 단조 전이) 만들었다.
 * 실제 MySQL 필요 (docker-compose up -d / CI 서비스 컨테이너).
 */
// RANDOM_PORT: 모의 서블릿 환경에는 WS ServerContainer가 없어 WS 설정 빈이 초기화되지 못한다 (contextLoads와 동일)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionRecordOrderingTest {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    private User owner;
    private User participant;
    private final List<String> createdCodes = new ArrayList<>();

    @BeforeEach
    void createUsers() {
        owner = userRepository.save(User.builder()
                .deviceId("ord-owner-" + UUID.randomUUID()).role(User.Role.USER).build());
        participant = userRepository.save(User.builder()
                .deviceId("ord-part-" + UUID.randomUUID()).role(User.Role.USER).build());
    }

    @AfterEach
    void cleanup() {
        createdCodes.forEach(code ->
                sessionRepository.findBySessionCode(code).ifPresent(sessionRepository::delete));
        createdCodes.clear();
        userRepository.delete(owner);
        userRepository.delete(participant);
    }

    @Test
    @DisplayName("create/join/end가 6가지 순열 중 무엇으로 도착해도 최종 기록은 동일하다")
    void anyArrivalOrder_producesSameFinalRecord() {
        int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

        for (int[] order : orders) {
            String code = newCode();
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
            Runnable[] events = {
                    () -> sessionService.createSession(code, owner.getId(), expiresAt),
                    () -> sessionService.joinSession(code, participant.getId(), "APP", owner.getId(), expiresAt),
                    () -> sessionService.endSession(code, owner.getId(), expiresAt)
            };

            for (int i : order) {
                events[i].run();
            }

            Session saved = sessionRepository.findBySessionCode(code).orElseThrow();
            String desc = "도착 순서 " + Arrays.toString(order);
            assertThat(saved.getStatus()).as(desc).isEqualTo("ENDED");
            assertThat(saved.getOwner().getId()).as(desc).isEqualTo(owner.getId());
            assertThat(saved.getParticipant().getId()).as(desc).isEqualTo(participant.getId());
            assertThat(saved.getConnectedAt()).as(desc).isNotNull();
            assertThat(saved.getEndedAt()).as(desc).isNotNull();
        }
    }

    @Test
    @DisplayName("create와 join이 동시에 행 생성을 시도해도 UNIQUE 심판 + 재실행(멱등)으로 행 1개에 수렴한다")
    void concurrentInsertRace_convergesToSingleRow() throws Exception {
        String code = newCode();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Runnable createEvent = () -> sessionService.createSession(code, owner.getId(), expiresAt);
        Runnable joinEvent = () ->
                sessionService.joinSession(code, participant.getId(), "APP", owner.getId(), expiresAt);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> raceThenRetryOnce(barrier, createEvent));
            Future<?> f2 = pool.submit(() -> raceThenRetryOnce(barrier, joinEvent));
            f1.get();
            f2.get();
        } finally {
            pool.shutdownNow();
        }

        Session saved = sessionRepository.findBySessionCode(code).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("CONNECTED");
        assertThat(saved.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(saved.getParticipant().getId()).isEqualTo(participant.getId());
    }

    /** 디스패처의 재시도 계약을 축약 재현 — UNIQUE 패자는 재실행되면 update 경로로 수렴한다 (멱등이라 안전). */
    private Void raceThenRetryOnce(CyclicBarrier barrier, Runnable event) throws Exception {
        barrier.await();
        try {
            event.run();
        } catch (DataIntegrityViolationException e) {
            event.run();
        }
        return null;
    }

    private String newCode() {
        String code = "OR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        createdCodes.add(code);
        return code;
    }
}
