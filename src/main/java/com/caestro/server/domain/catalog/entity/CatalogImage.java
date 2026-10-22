package com.caestro.server.domain.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 디자이너가 스튜디오에서 올린 그림 바이트(프레임 PNG, 배너 JPEG/PNG). 프레임은 수십 장 규모라 fill_images 와 같이
 * DB(LONGBLOB)에 두고, 내용이 바뀌지 않는 난수 id 로 오래 캐시한다. 프레임·배너가 지워지면 함께 지우고,
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

    @Lob
    @Column(nullable = false, columnDefinition = "LONGBLOB")
    private byte[] data;

    @Column(nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
