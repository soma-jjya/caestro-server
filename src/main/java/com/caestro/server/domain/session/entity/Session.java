package com.caestro.server.domain.session.entity;

import com.caestro.server.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "sessions")
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String sessionCode;

    // 방 생성자(owner). (현재 디렉터는 Redis SessionInfo가 관리)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    // 세션에 참여한 사람(participant).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id")
    private User participant;

    @Column(nullable = false, length = 20)
    private String cameraMode;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column
    private LocalDateTime connectedAt;

    @Column
    private LocalDateTime endedAt;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /**
     * 참여자 입장 마일스톤을 기록한다. 참여자·카메라 모드를 갱신하고 상태를 CONNECTED로 올린다.
     * 기록은 비동기 워커로 순서 없이 도착할 수 있으므로(#124) 상태는 단조 전이만 허용한다 —
     * end 기록 뒤에 늦게 도착해도 ENDED를 되살리지 않고, connectedAt은 최초 도달 시각을 유지한다.
     *
     * @param participantUser 입장한 참여자 유저 (null이면 기존 값 유지)
     * @param mode            카메라 모드 (APP)
     */
    public void connect(User participantUser, String mode) {
        if (participantUser != null) {
            this.participant = participantUser;
        }
        if (mode != null) {
            this.cameraMode = mode;
        }
        if (rank(this.status) < rank("CONNECTED")) {
            this.status = "CONNECTED";
        }
        if (this.connectedAt == null) {
            this.connectedAt = LocalDateTime.now();
        }
    }

    /**
     * 종료 마일스톤을 기록한다. 중복 도착에도 endedAt은 최초 종료 시각을 유지한다 (#124).
     */
    public void end() {
        if (rank(this.status) < rank("ENDED")) {
            this.status = "ENDED";
        }
        if (this.endedAt == null) {
            this.endedAt = LocalDateTime.now();
        }
    }

    // DB status는 실시간 상태가 아니라 도달 마일스톤 — 늦게 도착한 기록이 단계를 되돌리지 못한다 (#124)
    private static int rank(String status) {
        return switch (status) {
            case "ENDED" -> 2;
            case "CONNECTED" -> 1;
            default -> 0; // WAITING 및 그 외
        };
    }

    /**
     * 주어진 유저가 이 세션의 참여자(owner 또는 participant)인지 확인한다.
     *
     * @param userId 확인할 유저 ID
     * @return owner 또는 participant와 일치하면 true, 아니면 false
     */
    public boolean isParticipant(Long userId) {
        if (userId == null) {
            return false;
        }
        boolean isOwner = owner != null && userId.equals(owner.getId());
        boolean isJoiner = participant != null && userId.equals(participant.getId());
        return isOwner || isJoiner;
    }
}
