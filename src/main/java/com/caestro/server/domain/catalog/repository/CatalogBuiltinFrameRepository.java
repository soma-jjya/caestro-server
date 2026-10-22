package com.caestro.server.domain.catalog.repository;

import com.caestro.server.domain.catalog.entity.CatalogBuiltinFrame;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogBuiltinFrameRepository extends JpaRepository<CatalogBuiltinFrame, String> {

    List<CatalogBuiltinFrame> findAllByOrderByIdAsc();

    List<CatalogBuiltinFrame> findAllByDeletedAtIsNotNullOrderByDeletedAtDesc();
}
