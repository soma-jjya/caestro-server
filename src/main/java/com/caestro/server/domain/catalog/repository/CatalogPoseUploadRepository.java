package com.caestro.server.domain.catalog.repository;

import com.caestro.server.domain.catalog.entity.CatalogPoseUpload;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogPoseUploadRepository extends JpaRepository<CatalogPoseUpload, String> {
    List<CatalogPoseUpload> findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc();

    List<CatalogPoseUpload> findAllByDeletedAtIsNotNullOrderByDeletedAtDesc();

    List<CatalogPoseUpload> findAllByPurgeAtBefore(LocalDateTime now);
}
