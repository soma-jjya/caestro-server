package com.caestro.server.domain.catalog.repository;

import com.caestro.server.domain.catalog.entity.CatalogBanner;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogBannerRepository extends JpaRepository<CatalogBanner, String> {
    List<CatalogBanner> findAllByOrderBySortOrderAscCreatedAtAsc();
}
