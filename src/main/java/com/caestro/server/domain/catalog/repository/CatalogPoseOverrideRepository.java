package com.caestro.server.domain.catalog.repository;

import com.caestro.server.domain.catalog.entity.CatalogPoseOverride;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogPoseOverrideRepository extends JpaRepository<CatalogPoseOverride, Long> {
    Optional<CatalogPoseOverride> findByVersionAndShot(String version, String shot);

    List<CatalogPoseOverride> findAllByOrderByVersionAscShotAsc();

    List<CatalogPoseOverride> findAllByDeletedAtIsNotNullOrderByDeletedAtDesc();

    List<CatalogPoseOverride> findAllByPurgeAtBefore(LocalDateTime now);
}
