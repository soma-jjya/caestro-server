package com.caestro.server.domain.push.entity;

import com.caestro.server.domain.push.enums.PushPlatform;
import com.caestro.server.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 푸시를 보낼 기기 주소(FCM 토큰) (#206, contracts/push.md). 한 사용자가 기기 여럿이면 행 여럿.
 * 토큰은 "앱 설치 하나"를 가리키므로 전체에서 유일하고, 다른 계정으로 로그인하면 그 계정으로 옮겨 간다(마지막 로그인이 임자).
 * FCM 이 "없는 기기"라고 답하면 지우지 않고 {@code disabledAt} 으로 끈다 — 같은 토큰이 다시 등록되면 되살아난다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "push_tokens",
        uniqueConstraints = @UniqueConstraint(name = "uk_push_tokens_token", columnNames = "token"))
public class PushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PushPlatform platform;

    // FCM 등록 토큰. 실측 150~200자, 여유를 둔다
    @Column(nullable = false, length = 512)
    private String token;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    // 앱이 마지막으로 이 토큰을 등록(갱신)한 시각. 오래 안 보이는 토큰을 정리할 때 쓴다
    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    // FCM 이 UNREGISTERED 를 답한 시각. null 이면 보낼 수 있는 토큰
    private LocalDateTime disabledAt;

    /** 같은 토큰을 다시 등록: 지금 로그인한 계정의 것으로 삼고 되살린다. */
    public void reassign(User user, PushPlatform platform, LocalDateTime now) {
        this.user = user;
        this.platform = platform;
        this.lastSeenAt = now;
        this.disabledAt = null;
    }

    public void disable(LocalDateTime now) {
        this.disabledAt = now;
    }

    public boolean isActive() {
        return disabledAt == null;
    }
}
