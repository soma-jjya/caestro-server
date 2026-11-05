package com.caestro.server.domain.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 디자이너가 스튜디오에서 올린 포즈추천 AI 사진. 앱은 찾아보기 목록에 섞어 보이고 가이드로 고르면 기기에서 관절을 계산한다;
 * 유사도 추천(index)에는 파이프라인이 다음 버전에 넣기 전까지 들어가지 않는다. 라벨은 앱 meta 와 같은 한국어 토큰(장소·성별·인종·계절).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "catalog_pose_uploads")
public class CatalogPoseUpload {

    @Id
    @Column(length = 32)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_id", nullable = false)
    private CatalogImage image;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String labelsJson;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean hidden;

    private LocalDateTime deletedAt;

    private LocalDateTime purgeAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public boolean isTrashed() {
        return deletedAt != null;
    }

    public void setLabelsJson(String labelsJson) {
        this.labelsJson = labelsJson;
    }

    public void replaceImage(CatalogImage image) {
        this.image = image;
        this.width = image.getWidth();
        this.height = image.getHeight();
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public void moveToTrash(LocalDateTime now, LocalDateTime purgeAt) {
        this.deletedAt = now;
        this.purgeAt = purgeAt;
        this.updatedAt = now;
    }

    public void restore(LocalDateTime now) {
        this.deletedAt = null;
        this.purgeAt = null;
        this.updatedAt = now;
    }

    public void touch(LocalDateTime now) {
        this.updatedAt = now;
    }
}
