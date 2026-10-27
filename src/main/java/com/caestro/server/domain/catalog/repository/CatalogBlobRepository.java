package com.caestro.server.domain.catalog.repository;

import com.caestro.server.domain.catalog.entity.CatalogBlob;
import org.springframework.data.jpa.repository.JpaRepository;

/** RDS 자산 저장소 전용. 서비스 코드는 {@code CatalogAssetStore} 를 통해서만 바이트를 다룬다. */
public interface CatalogBlobRepository extends JpaRepository<CatalogBlob, String> {
}
