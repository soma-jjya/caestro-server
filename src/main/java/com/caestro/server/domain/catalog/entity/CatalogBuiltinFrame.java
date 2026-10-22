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
 * 앱에 들어 있는 기본 프레임(검정·흰색, AI 프레임 7개)을 스튜디오에서 덮어쓴 값(카탈로그 5차).
 * 행이 없거나 값이 null 이면 앱에 든 그대로다. 프레임 id 는 앱과 같다(Black, White, gyaru, …) — 이미 만든 네컷이 그대로 이어진다.
 * 그림·창은 AI 프레임만 바꿀 수 있다(검정·흰색은 그림이 없다). 삭제는 휴지통에 넣을 뿐이고 정리 배치가 지우지 않는다
 * (앱에서 프레임을 없앨 수는 없으니 숨김으로 남는다). 되살리면 다시 보인다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "catalog_builtin_frames")
public class CatalogBuiltinFrame {

    @Id
    @Column(length = 32)
    private String id;

    @Column(length = 40)
    private String titleKo;

    @Column(length = 60)
    private String titleEn;

    @Column(length = 80)
    private String subtitleKo;

    @Column(length = 120)
    private String subtitleEn;

    /** Pro 전용인가. null 이면 앱 기본값(검정·흰색·갸루 야호·코지 패치워크는 무료, 나머지는 Pro) */
    private Boolean pro;

    @Column(length = 20)
    private String badgeKo;

    @Column(length = 40)
    private String badgeEn;

    @Column(nullable = false)
    private boolean hidden;

    /** 바꾼 그림(AI 프레임만). null 이면 앱에 든 그림 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_id")
    private CatalogImage image;

    private Integer width;

    private Integer height;

    @Column(columnDefinition = "TEXT")
    private String windowsJson;

    /** 휴지통에 넣은 시각. null 이면 살아 있다 */
    private LocalDateTime deletedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public boolean isTrashed() {
        return deletedAt != null;
    }

    public void setTitle(String ko, String en) {
        this.titleKo = ko;
        this.titleEn = en;
    }

    public void setSubtitle(String ko, String en) {
        this.subtitleKo = ko;
        this.subtitleEn = en;
    }

    public void setPro(Boolean pro) {
        this.pro = pro;
    }

    public void setBadge(String ko, String en) {
        this.badgeKo = ko;
        this.badgeEn = en;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    /** 그림과 창을 함께 바꾼다. image 가 null 이면 앱에 든 그림·창으로 돌아간다 */
    public void setArtwork(CatalogImage image, String windowsJson) {
        this.image = image;
        this.width = image == null ? null : image.getWidth();
        this.height = image == null ? null : image.getHeight();
        this.windowsJson = image == null ? null : windowsJson;
    }

    public void setWindowsJson(String windowsJson) {
        this.windowsJson = windowsJson;
    }

    public void moveToTrash(LocalDateTime now) {
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public void restore(LocalDateTime now) {
        this.deletedAt = null;
        this.updatedAt = now;
    }

    public void touch(LocalDateTime now) {
        this.updatedAt = now;
    }
}
