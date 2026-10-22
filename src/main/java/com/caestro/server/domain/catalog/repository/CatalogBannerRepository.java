package com.caestro.server.domain.catalog.repository;

import com.caestro.server.domain.catalog.entity.CatalogBanner;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogBannerRepository extends JpaRepository<CatalogBanner, String> {
    /** 살아 있는(휴지통 아닌) 배너. */
    List<CatalogBanner> findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc();

    List<CatalogBanner> findAllByDeletedAtIsNotNullOrderByDeletedAtDesc();

    List<CatalogBanner> findAllByPurgeAtBefore(LocalDateTime now);

    /** 앱에 보이는 배너 수: 숨김 아님 + 휴지통 아님. 마지막 하나는 숨기거나 지울 수 없다. */
    long countByHiddenFalseAndDeletedAtIsNull();
}
