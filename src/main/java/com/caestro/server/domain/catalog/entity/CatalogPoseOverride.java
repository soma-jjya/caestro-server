package com.caestro.server.domain.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 파이프라인이 S3 에 올린 포즈 풀의 샷 하나에 덧씌우는 값: 숨김, 더한 라벨(성별·인종·계절).
 * S3 의 파일은 건드리지 않는다(index.bin 의 길이가 meta 의 샷 수와 묶여 있어 한 장만 빼도 깨진다).
 * 휴지통(삭제)은 숨김 + deletedAt 이며, 기한이 지나도 S3 를 지울 수 없으므로 숨김으로 영구히 남긴다.
 * 숨김도 라벨도 휴지통도 아니면 행을 지운다 — 덧씌울 것이 없다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "catalog_pose_overrides", uniqueConstraints = @UniqueConstraint(name = "uk_pose_override", columnNames = {"version", "shot"}))
public class CatalogPoseOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(nullable = false, length = 80)
    private String shot;

    @Column(nullable = false)
    private boolean hidden;

    @Column(columnDefinition = "TEXT")
    private String labelsJson;

    private LocalDateTime deletedAt;

    private LocalDateTime purgeAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public boolean isTrashed() {
        return deletedAt != null;
    }

    /** 덧씌울 것이 하나도 없는 행 — 지워도 된다. */
    public boolean isEmpty() {
        return !hidden && labelsJson == null && deletedAt == null;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public void setLabelsJson(String labelsJson) {
        this.labelsJson = labelsJson;
    }

    public void moveToTrash(LocalDateTime now, LocalDateTime purgeAt) {
        this.hidden = true;
        this.deletedAt = now;
        this.purgeAt = purgeAt;
        this.updatedAt = now;
    }

    public void restore(LocalDateTime now) {
        this.hidden = false;
        this.deletedAt = null;
        this.purgeAt = null;
        this.updatedAt = now;
    }

    /** 기한이 지난 휴지통 행: S3 는 못 지우니 숨김으로 영구히 남긴다. */
    public void purgeKeepHidden(LocalDateTime now) {
        this.hidden = true;
        this.deletedAt = null;
        this.purgeAt = null;
        this.updatedAt = now;
    }

    public void touch(LocalDateTime now) {
        this.updatedAt = now;
    }
}
