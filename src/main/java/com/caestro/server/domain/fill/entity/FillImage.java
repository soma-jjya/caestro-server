package com.caestro.server.domain.fill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
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
 * 세션에 올라온 이미지 바이트(칸 사진 JPEG, 커스텀 프레임 요소 PNG). 클라이언트가 긴 변 1600px로 줄여 올리므로
 * 한 장이 수백 KB이고 세션당 4~6장, 7일이면 사라진다. 이 규모에서는 별도 저장소 없이 DB에 두는 것이 운영이 가장 단순하다;
 * 규모가 커지면 이 엔티티 뒤를 S3로 바꾸면 되고 URL 형식(/fills/{code}/images/{id})은 그대로다.
 * 접근은 인증 없이 id로 한다 — id가 22자 난수이고 세션 코드까지 맞아야 하므로 추측할 수 없다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "fill_images")
public class FillImage {

    @Id
    @Column(length = 32)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private FillSession session;

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
