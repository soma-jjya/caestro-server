package com.caestro.server.domain.support.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/** 문의 사진 바이트의 RDS 보관 행 (#263). 로컬·CI 기본이고, 운영은 S3 구현이라 이 표가 비어 있다. */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "support_attachment_blobs", indexes = @Index(name = "idx_support_attachment_blobs_ticket", columnList = "ticket_id"))
public class SupportAttachmentBlob {

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(nullable = false, length = 40)
    private String contentType;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGBLOB")
    private byte[] data;

    @Column(nullable = false)
    private int sizeBytes;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
