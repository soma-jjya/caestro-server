package com.caestro.server.domain.catalog.repository;

import com.caestro.server.domain.catalog.entity.CatalogFrame;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogFrameRepository extends JpaRepository<CatalogFrame, String> {
    List<CatalogFrame> findAllByOrderBySortOrderAscCreatedAtAsc();
}
