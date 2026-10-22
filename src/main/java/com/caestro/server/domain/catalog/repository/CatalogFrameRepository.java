package com.caestro.server.domain.catalog.repository;

import com.caestro.server.domain.catalog.entity.CatalogFrame;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogFrameRepository extends JpaRepository<CatalogFrame, String> {
    /** 살아 있는(휴지통 아닌) 프레임. */
    List<CatalogFrame> findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc();

    List<CatalogFrame> findAllByDeletedAtIsNotNullOrderByDeletedAtDesc();

    List<CatalogFrame> findAllByPurgeAtBefore(LocalDateTime now);
}
