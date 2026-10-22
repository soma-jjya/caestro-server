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
 * 서버에 올라온 일러스트 네컷 프레임. 앱에 번들된 기본 프레임 뒤에 이어 붙는다.
 * 사진이 들어갈 창 4개는 디자이너가 배치한 비율 사각형(회전 포함)이며 JSON 으로 둔다 — 앱·웹이 그대로 그린다.
 * 숨김({@code hidden})은 목록에서만 빠지고 id 로는 계속 열린다(세션이 참조 중일 수 있다).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "catalog_frames")
public class CatalogFrame {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 40)
    private String titleKo;

    @Column(length = 60)
    private String titleEn;

    @Column(length = 80)
    private String subtitleKo;

    @Column(length = 120)
    private String subtitleEn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_id", nullable = false)
    private CatalogImage image;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String windowsJson;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean hidden;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public void rename(String titleKo, String titleEn, String subtitleKo, String subtitleEn) {
        this.titleKo = titleKo;
        this.titleEn = titleEn;
        this.subtitleKo = subtitleKo;
        this.subtitleEn = subtitleEn;
    }

    public void replaceImage(CatalogImage image) {
        this.image = image;
        this.width = image.getWidth();
        this.height = image.getHeight();
    }

    public void setWindowsJson(String windowsJson) {
        this.windowsJson = windowsJson;
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
