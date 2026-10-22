package com.caestro.server.domain.catalog.repository;

import com.caestro.server.domain.catalog.entity.CatalogImage;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogImageRepository extends JpaRepository<CatalogImage, String> {

    // 어떤 프레임·배너·포즈 업로드도 쓰지 않는 그림(올리고 저장을 안 한 것). 정리 배치가 하루 지난 것부터 지운다.
    // 휴지통에 있는 행도 그림을 쓰고 있으므로(복구될 수 있다) 지우지 않는다
    @Query("select i.id from CatalogImage i where i.createdAt < :before"
            + " and not exists (select f from CatalogFrame f where f.image = i)"
            + " and not exists (select b from CatalogBanner b where b.image = i)"
            + " and not exists (select p from CatalogPoseUpload p where p.image = i)")
    List<String> findOrphanIds(@Param("before") LocalDateTime before);
}
