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
 * 앱 홈 맨 위 배너 한 장: 그림 + 제목·본문·칩 문구(ko/en) + 누르면 가는 곳({@code action}).
 * 보이는 배너가 둘 이상이면 앱이 자동으로 넘긴다. 하나도 없으면 앱은 번들된 기본 배너를 보인다.
 * 지우면 바로 없어지지 않고 휴지통({@code deletedAt}, {@code purgeAt})에 들어갔다가 정리 배치가 완전히 지운다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "catalog_banners")
public class CatalogBanner {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 60)
    private String titleKo;

    @Column(length = 90)
    private String titleEn;

    @Column(length = 120)
    private String bodyKo;

    @Column(length = 180)
    private String bodyEn;

    @Column(length = 20)
    private String chipKo;

    @Column(length = 30)
    private String chipEn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_id", nullable = false)
    private CatalogImage image;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    // poseGuide | fourCut | fillTogether | frameLibrary | url | null(동작 없음)
    @Column(length = 20)
    private String action;

    @Column(length = 500)
    private String linkUrl;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean hidden;

    // 휴지통: 둘 다 null 이면 살아 있는 행. purgeAt 이 지나면 정리 배치가 완전히 지운다
    private LocalDateTime deletedAt;

    private LocalDateTime purgeAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public boolean isTrashed() {
        return deletedAt != null;
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

    public void setTitle(String ko, String en) {
        this.titleKo = ko;
        this.titleEn = en;
    }

    public void setBody(String ko, String en) {
        this.bodyKo = ko;
        this.bodyEn = en;
    }

    public void setChip(String ko, String en) {
        this.chipKo = ko;
        this.chipEn = en;
    }

    public void replaceImage(CatalogImage image) {
        this.image = image;
        this.width = image.getWidth();
        this.height = image.getHeight();
    }

    public void setAction(String action, String linkUrl) {
        this.action = action;
        this.linkUrl = linkUrl;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public void touch(LocalDateTime now) {
        this.updatedAt = now;
    }
}
