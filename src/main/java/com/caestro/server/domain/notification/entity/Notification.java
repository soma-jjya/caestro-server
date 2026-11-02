package com.caestro.server.domain.notification.entity;

import com.caestro.server.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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

/**
 * 알림함의 한 줄 (#211, contracts/notifications.md). 푸시를 보내기 전에 먼저 저장되고, 푸시를 못 받은 사용자도 앱을 열면 여기서 본다.
 * {@code data} 는 앱이 어디로 갈지 정하는 값(JSON, 예: {"code":"K7X2MQ"}) 으로 푸시의 data 와 같다. 읽음은 {@code readAt} 시각으로 남긴다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_user_id", columnList = "user_id, id"),
        @Index(name = "idx_notifications_user_read", columnList = "user_id, read_at"),
        @Index(name = "idx_notifications_created", columnList = "created_at")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // PushMessage.type 과 같은 값(fill_completed 등). 앱의 이동 규칙과 지표 태그가 이걸 본다
    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 300)
    private String body;

    // 푸시 data 와 같은 JSON 문자열. 비면 "{}"
    @Column(nullable = false, length = 1000)
    private String data;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    // 사용자가 연 시각. null 이면 안 읽음
    private LocalDateTime readAt;

    /** 처음 읽을 때만 시각을 남긴다. 이미 읽은 알림을 다시 열어도 바뀌지 않는다. */
    public boolean markRead(LocalDateTime now) {
        if (readAt != null) {
            return false;
        }
        readAt = now;
        return true;
    }

    public boolean isRead() {
        return readAt != null;
    }
}
