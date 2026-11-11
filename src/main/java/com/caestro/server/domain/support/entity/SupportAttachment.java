package com.caestro.server.domain.support.entity;

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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 문의에 붙은 사진의 메타 (#263). 바이트는 {@link com.caestro.server.domain.support.store.SupportAttachmentStore} 가 {@code storeId} 로 든다 —
 * 네컷 이미지(#152)와 같은 분리라 RDS 에서 S3 로 바꿔도 이 행과 주소 형식은 그대로다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "support_attachments", indexes = @Index(name = "idx_support_attachments_ticket", columnList = "ticket_id, id"))
public class SupportAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private SupportTicket ticket;

    /** 저장소 안의 난수 키(22자). 주소에는 이 값이 아니라 행 id 가 들어간다. */
    @Column(nullable = false, length = 32)
    private String storeId;

    @Column(nullable = false, length = 40)
    private String contentType;

    @Column(nullable = false)
    private int sizeBytes;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
