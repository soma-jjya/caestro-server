package com.caestro.server.domain.fill.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.dto.response.FillResponse;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.fill.store.FillImageStore;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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

/**
 * #151 핵심 증명 — 두 사람이 같은 빈 칸을 동시에 채워도 "먼저 채운 사람 것" 규칙이 지켜진다.
 * 잠금 없이 읽으면 둘 다 "비었다"를 통과해 둘 다 저장하고, 나중 것이 칸을 가져가며 먼저 올린 사진이 고아가 됐다.
 * 칸 행을 FOR UPDATE로 읽어 패자가 사진을 저장하기 전에 409로 끝나게 했다.
 * 실제 MySQL 필요 (docker-compose up -d / CI 서비스 컨테이너).
 */
// RANDOM_PORT: 모의 서블릿 환경에는 WS ServerContainer가 없어 WS 설정 빈이 초기화되지 못한다 (contextLoads와 동일)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FillSlotRaceTest {

    private static final byte[] JPEG_A = jpeg((byte) 0x11);
    private static final byte[] JPEG_B = jpeg((byte) 0x22);

    @Autowired
    private FillService fillService;

    @Autowired
    private FillSessionRepository sessionRepository;

    @Autowired
    private FillImageStore imageStore;

    @Autowired
    private UserRepository userRepository;

    private User owner;
    private User friend;
    private final List<String> createdCodes = new ArrayList<>();

    private static byte[] jpeg(byte filler) {
        byte[] bytes = new byte[512];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        for (int i = 4; i < bytes.length; i++) {
            bytes[i] = filler;
        }
        return bytes;
    }

    @BeforeEach
    void createUsers() {
        owner = userRepository.save(User.builder()
                .deviceId("race-owner-" + UUID.randomUUID()).nickname("주인").role(User.Role.USER).build());
        friend = userRepository.save(User.builder()
                .deviceId("race-friend-" + UUID.randomUUID()).nickname("친구").role(User.Role.USER).build());
    }

    @AfterEach
    void cleanup() {
        createdCodes.forEach(code -> {
            try {
                fillService.delete(code, owner.getId());
            } catch (CustomException ignored) {
                // 이미 지워졌으면 넘어간다
            }
        });
        createdCodes.clear();
        userRepository.delete(owner);
        userRepository.delete(friend);
    }

    @Test
    @DisplayName("[재현→방어] 같은 빈 칸에 동시 요청이 오면 한쪽만 성공하고 패자는 409 — 고아 사진이 남지 않는다")
    void concurrentFill_onlyOneWins_andNoOrphanImage() throws Exception {
        FillResponse created = fillService.create(owner.getId(),
                new CreateFillRequest("Black", "Strip", null, null, "경합 테스트", null));
        String code = created.code();
        createdCodes.add(code);
        Long sessionId = sessionRepository.findByCode(code).orElseThrow().getId();

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<ErrorCode> byOwner = attempt(barrier, owner.getId(), JPEG_A);
        Callable<ErrorCode> byFriend = attempt(barrier, friend.getId(), JPEG_B);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<ErrorCode> outcomes;
        try {
            Future<ErrorCode> f1 = pool.submit(byOwner);
            Future<ErrorCode> f2 = pool.submit(byFriend);
            outcomes = Arrays.asList(f1.get(), f2.get());
        } finally {
            pool.shutdownNow();
        }

        // 한쪽은 성공(null), 다른 쪽은 FILL_SLOT_TAKEN — 둘 다 성공하면 규칙이 깨진 것이다
        assertThat(outcomes).containsExactlyInAnyOrder(null, ErrorCode.FILL_SLOT_TAKEN);

        // 칸은 하나이고 사진도 하나여야 한다. 둘 다 저장됐다면 2가 나오고 그중 하나가 고아다
        FillResponse after = fillService.get(code, owner.getId());
        assertThat(after.slots().get(0).imageUrl()).isNotNull();
        assertThat(after.slots().subList(1, 4)).allSatisfy(s -> assertThat(s.imageUrl()).isNull());
        assertThat(imageStore.countBySession(sessionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("[회귀] 서로 다른 칸은 동시에 채워도 둘 다 성공한다 — 잠금이 칸 단위라 서로 막지 않는다")
    void concurrentFill_differentSlots_bothSucceed() throws Exception {
        FillResponse created = fillService.create(owner.getId(),
                new CreateFillRequest("White", "Grid", null, null, null, null));
        String code = created.code();
        createdCodes.add(code);
        Long sessionId = sessionRepository.findByCode(code).orElseThrow().getId();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<ErrorCode> outcomes;
        try {
            Future<ErrorCode> f1 = pool.submit(attempt(barrier, owner.getId(), 0, JPEG_A));
            Future<ErrorCode> f2 = pool.submit(attempt(barrier, friend.getId(), 1, JPEG_B));
            outcomes = Arrays.asList(f1.get(), f2.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).containsExactly(null, null);
        assertThat(imageStore.countBySession(sessionId)).isEqualTo(2);
    }

    @Test
    @DisplayName("[회귀] 본인 칸 교체 시 이전 사진이 지워진다 — 칸이 id만 들게 바뀐 뒤에도 삭제 순서가 맞는다 (#152)")
    void replaceOwnSlot_deletesPreviousImage() {
        FillResponse created = fillService.create(owner.getId(),
                new CreateFillRequest("Black", "Strip", null, null, null, null));
        String code = created.code();
        createdCodes.add(code);
        Long sessionId = sessionRepository.findByCode(code).orElseThrow().getId();

        String first = fillService.fillSlot(code, 0, owner.getId(), null, JPEG_A, "image/jpeg")
                .slots().get(0).imageUrl();
        String second = fillService.fillSlot(code, 0, owner.getId(), null, JPEG_B, "image/jpeg")
                .slots().get(0).imageUrl();

        assertThat(second).isNotEqualTo(first);
        assertThat(imageStore.countBySession(sessionId)).isEqualTo(1);
    }

    /** 칸 0을 동시에 노린다. 성공하면 null, 거절되면 그 ErrorCode를 돌려준다. */
    private Callable<ErrorCode> attempt(CyclicBarrier barrier, Long userId, byte[] body) {
        return attempt(barrier, userId, 0, body);
    }

    private Callable<ErrorCode> attempt(CyclicBarrier barrier, Long userId, int index, byte[] body) {
        return () -> {
            barrier.await();
            try {
                fillService.fillSlot(lastCode(), index, userId, null, body, "image/jpeg");
                return null;
            } catch (CustomException e) {
                return e.getErrorCode();
            }
        };
    }

    private String lastCode() {
        return createdCodes.get(createdCodes.size() - 1);
    }
}
