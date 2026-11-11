package com.caestro.server.domain.support.entity;

import com.caestro.server.domain.support.enums.SupportCategory;
import com.caestro.server.domain.support.enums.SupportStatus;
import com.caestro.server.domain.user.entity.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 앱 문의 한 건 = 대화 스레드 (#263, contracts/support.md). 메시지({@link SupportMessage})와 사진({@link SupportAttachment})은
 * 이 행에 매달려 함께 지워진다. 사용자는 로그인 계정만 — 탈퇴하면 User 쪽이 익명화되고 이 행은 보관 기간까지 남는다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "support_tickets", indexes = {
        @Index(name = "idx_support_tickets_user", columnList = "user_id, id"),
        @Index(name = "idx_support_tickets_status", columnList = "status, id"),
        @Index(name = "idx_support_tickets_last_message", columnList = "last_message_at")
})
public class SupportTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SupportCategory category;

    @Column(nullable = false, length = 80)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SupportStatus status;

    // 앱이 자동으로 붙이는 기기 정보. 운영자가 재현할 때 본다
    @Column(length = 30)
    private String appVersion;

    @Column(length = 10)
    private String platform;

    @Column(length = 40)
    private String osVersion;

    @Column(length = 60)
    private String deviceModel;

    /** 운영자가 답한 뒤 사용자가 아직 상세를 열지 않았다. 목록의 "답변 도착" 표시. */
    @Column(nullable = false)
    private boolean userUnread;

    /** 첫 답변 시각. 응답 시간 지표의 재료. */
    private LocalDateTime firstAnsweredAt;

    private LocalDateTime closedAt;

    /** 마지막 메시지(어느 쪽이든) 시각. 보관 기간의 기준. */
    @Column(nullable = false)
    private LocalDateTime lastMessageAt;

    @Column(length = 2000)
    private String adminMemo;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id asc")
    @Builder.Default
    private List<SupportMessage> messages = new ArrayList<>();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id asc")
    @Builder.Default
    private List<SupportAttachment> attachments = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    /** 사용자가 이어 썼다: 어느 상태였든 운영자 차례(OPEN)로. */
    public void touchByUser(LocalDateTime now) {
        this.status = SupportStatus.OPEN;
        this.closedAt = null;
        this.lastMessageAt = now;
    }

    /** 운영자가 답했다: ANSWERED, 사용자에게 안 읽음 표시, 첫 답변이면 시각 기록. */
    public void answered(LocalDateTime now) {
        this.status = SupportStatus.ANSWERED;
        this.userUnread = true;
        this.lastMessageAt = now;
        if (this.firstAnsweredAt == null) {
            this.firstAnsweredAt = now;
        }
    }

    public void close(LocalDateTime now) {
        this.status = SupportStatus.CLOSED;
        this.closedAt = now;
    }

    public void reopen() {
        this.status = SupportStatus.OPEN;
        this.closedAt = null;
    }

    public void markReadByUser() {
        this.userUnread = false;
    }

    public void memo(String adminMemo) {
        this.adminMemo = adminMemo;
    }
}
