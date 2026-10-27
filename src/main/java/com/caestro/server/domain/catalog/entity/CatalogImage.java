package com.caestro.server.domain.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 디자이너가 스튜디오에서 올린 그림(프레임 PNG, 배너 JPEG/PNG)·배너 동영상(MP4, {@code durationMs})의 메타데이터.
 * 바이트는 {@code CatalogAssetStore}(#186: RDS 는 catalog_blobs, 운영은 S3)에 같은 id 로 있다. 내용이 바뀌지 않는 난수 id 로 오래 캐시한다. 프레임·배너가 지워지면 함께 지우고,
 * 올리기만 하고 저장하지 않은 것은 하루 뒤 정리한다({@code CatalogCleanupScheduler}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "catalog_images")
public class CatalogImage {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 40)
    private String contentType;

    @Column(nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    // 동영상(video/mp4)일 때만: 재생 길이(ms). 앱이 배너를 이 길이만큼 보여 준다
    private Integer durationMs;

    public boolean isVideo() {
        return contentType != null && contentType.startsWith("video/");
    }

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
