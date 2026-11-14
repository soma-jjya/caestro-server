package com.caestro.server.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 알림 아웃박스 (#272, contracts/notifications.md "아웃박스"). 알림을 보내야 할 **사건과 같은 트랜잭션**에 한 행을 남긴다 —
 * 커밋됐으면 요청도 반드시 남고, 릴레이가 집어 보낸다. 제목·본문·data 를 그대로 들고 있어 보낼 때 도메인을 다시 읽지 않는다.
 * 사용자는 id 만 든다(FK 없음): 탈퇴·정리 배치와 얽히지 않게.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "notification_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_outbox_dedup", columnNames = "dedup_key"),
        indexes = {
                @Index(name = "idx_notification_outbox_due", columnList = "status, not_before, id"),
                @Index(name = "idx_notification_outbox_collapse", columnList = "collapse_key, status"),
                @Index(name = "idx_notification_outbox_sent", columnList = "status, sent_at")
        })
public class NotificationOutbox {

    public static final int DATA_MAX = 1000;
    public static final int ERROR_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String body;

    /** 푸시 data(JSON). 알림함 행과 FCM 에 그대로 실린다. */
    @Column(nullable = false, length = DATA_MAX)
    private String data;

    /** 같은 알림 두 번 금지의 키(예 {@code fill_completed:12:7}). 유니크. */
    @Column(name = "dedup_key", nullable = false, length = 120)
    private String dedupKey;

    /** 묶어 보낼 때의 키(#274). 지금은 비어 있다. */
    @Column(name = "collapse_key", length = 120)
    private String collapseKey;

    /** 이 시각 전에는 보내지 않는다. 재시도 백오프도 여기에 적는다. */
    @Column(name = "not_before", nullable = false)
    private LocalDateTime notBefore;

    /** 이 시각이 지나면 보내지 않고 EXPIRED(만료 안내처럼 때를 놓치면 의미 없는 알림). null 이면 시한 없음. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    /** PROCESSING 인 동안 이 시각까지는 다른 릴레이가 건드리지 않는다. 지나면 죽은 릴레이의 것으로 보고 되찾는다. */
    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    /** 집어 간 릴레이의 표. 같은 묶음을 다시 읽을 때 쓴다. */
    @Column(name = "claim_token", length = 36)
    private String claimToken;

    /** 알림함 행 id. 첫 시도에 만들고 재시도에서는 다시 만들지 않는다(알림함에 두 줄이 안 생기게). */
    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "last_error", length = ERROR_MAX)
    private String lastError;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    public void inboxRecorded(Long notificationId) {
        this.notificationId = notificationId;
    }

    public void sent(LocalDateTime now) {
        this.status = OutboxStatus.SENT;
        this.sentAt = now;
        this.claimToken = null;
        this.leaseUntil = null;
        this.lastError = null;
    }

    public void expired(LocalDateTime now) {
        this.status = OutboxStatus.EXPIRED;
        this.sentAt = now;
        this.claimToken = null;
        this.leaseUntil = null;
    }

    /** 실패: 한 번 더 세고 다음 시각을 적어 PENDING 으로 돌린다. 한도를 넘으면 DEAD. */
    public void failed(LocalDateTime now, LocalDateTime retryAt, int maxAttempts, String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), ERROR_MAX));
        this.claimToken = null;
        this.leaseUntil = null;
        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.DEAD;
            this.sentAt = now;
        } else {
            this.status = OutboxStatus.PENDING;
            this.notBefore = retryAt;
        }
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
