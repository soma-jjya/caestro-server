package com.caestro.server.domain.session.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.caestro.server.domain.user.entity.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 세션 DB 기록은 실시간 상태가 아니라 "도달 마일스톤 이력"이다 (#124).
 * 기록은 비동기 워커로 도착하고, 멀티 인스턴스에서는 create(방장 인스턴스)와 join/end(참여자
 * 인스턴스)가 서로 다른 큐를 타므로 도착 순서 역전이 실제로 일어난다 — 늦게 도착한 기록이
 * 마일스톤을 되돌리면(ENDED 부활, 최초 시각 덮어쓰기) 안 된다.
 */
class SessionMilestoneTest {

    @Test
    @DisplayName("[재현] end 뒤에 늦게 도착한 connect가 ENDED를 CONNECTED로 되살리면 안 된다")
    void lateConnect_mustNotResurrectEndedSession() {
        Session session = waitingSession();

        session.end();
        session.connect(user(2L), "APP"); // ms 단위 경합에서 join 기록이 end보다 늦게 커밋된 상황

        assertThat(session.getStatus()).isEqualTo("ENDED");
        assertThat(session.getEndedAt()).isNotNull();
    }

    @Test
    @DisplayName("[재현] connectedAt은 최초 연결 시각을 유지한다 — 중복 도착이 덮어쓰지 않는다")
    void connectedAt_keepsFirstValue() throws InterruptedException {
        Session session = waitingSession();

        session.connect(user(2L), "APP");
        LocalDateTime first = session.getConnectedAt();
        Thread.sleep(5);
        session.connect(user(2L), "APP"); // 재시도·중복 전달로 같은 기록이 다시 도착

        assertThat(session.getConnectedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("[재현] endedAt은 최초 종료 시각을 유지한다")
    void endedAt_keepsFirstValue() throws InterruptedException {
        Session session = waitingSession();

        session.end();
        LocalDateTime first = session.getEndedAt();
        Thread.sleep(5);
        session.end();

        assertThat(session.getEndedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("정상 순서(connect→end)는 그대로 동작한다 (회귀 가드)")
    void normalOrder_stillWorks() {
        Session session = waitingSession();

        session.connect(user(2L), "APP");
        session.end();

        assertThat(session.getStatus()).isEqualTo("ENDED");
        assertThat(session.getParticipant().getId()).isEqualTo(2L);
        assertThat(session.getConnectedAt()).isNotNull();
        assertThat(session.getEndedAt()).isNotNull();
    }

    private Session waitingSession() {
        return Session.builder()
                .sessionCode("MILE01")
                .owner(user(1L))
                .cameraMode("APP")
                .status("WAITING")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
    }

    private User user(Long id) {
        User user = User.builder().role(User.Role.USER).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
