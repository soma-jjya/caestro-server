package com.caestro.server.domain.b2b.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 프로젝트 삭제와 함께 커밋되는 저장소 정리 기록. 삭제된 행에 대한 FK는 두지 않는다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "b2b_asset_deletions", indexes = @Index(name = "idx_b2b_deletion_due", columnList = "next_attempt_at,id"))
public class B2bAssetDeletion {
    public enum Kind { FILL_SESSION, FRAME_IMAGE }

    @Id @Column(length = 80)
    private String id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Kind kind;
    @Column(nullable = false, length = 40)
    private String targetId;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    /** 실패한 저장소 작업의 다음 시도를 늦춰 다른 작업이 먼저 처리되게 한다. */
    public void deferUntil(LocalDateTime next) { this.nextAttemptAt = next; }
}
