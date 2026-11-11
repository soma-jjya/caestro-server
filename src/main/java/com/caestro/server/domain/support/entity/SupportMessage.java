package com.caestro.server.domain.support.entity;

import com.caestro.server.domain.support.enums.SupportAuthor;
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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/** 문의 스레드의 말 한 마디 (#263). 사용자 글도 운영자 답변도 같은 표다. 고치거나 지우지 않는다. */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "support_messages", indexes = @Index(name = "idx_support_messages_ticket", columnList = "ticket_id, id"))
public class SupportMessage {

    public static final int BODY_MAX = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private SupportTicket ticket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private SupportAuthor author;

    @Column(nullable = false, length = BODY_MAX)
    private String body;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
