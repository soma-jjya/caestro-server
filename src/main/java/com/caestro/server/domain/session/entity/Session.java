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
     * 참여자(participant)가 세션에 입장했을 때 세션 상태를 연결됨(CONNECTED)으로 전환한다.
     * 참여자 유저, 카메라 모드, 연결 시각을 함께 갱신한다.
     *
     * @param participantUser 입장한 참여자 유저
     * @param mode            카메라 모드 (APP)
     */
    public void connect(User participantUser, String mode) {
        this.participant = participantUser;
        this.cameraMode = mode;
        this.status = "CONNECTED";
        this.connectedAt = LocalDateTime.now();
    }

    /**
     * 세션을 종료(ENDED) 상태로 전환하고 종료 시각을 기록한다.
     */
    public void end() {
        this.status = "ENDED";
        this.endedAt = LocalDateTime.now();
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
