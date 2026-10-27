package com.caestro.server.domain.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * RDS 자산 저장소({@code RdsCatalogAssetStore})의 바이트 행 (#186). 로컬·CI 처럼 S3 가 없는 환경용이다.
 * 메타데이터는 {@code catalog_images}·{@code b2b_images} 에 있고 여기엔 바이트만 있다. id 는 그 메타 행의 id 와 같다.
 * 운영(S3)에서는 이 테이블이 비어 있다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "catalog_blobs")
public class CatalogBlob {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 40)
    private String contentType;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGBLOB")
    private byte[] data;
}
