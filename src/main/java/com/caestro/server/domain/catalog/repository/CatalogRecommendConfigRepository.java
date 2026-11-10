package com.caestro.server.domain.catalog.repository;

import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.entity.CatalogRecommendConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogRecommendConfigRepository extends JpaRepository<CatalogRecommendConfig, String> {

    List<CatalogRecommendConfig> findAllByOrderByCreatedAtDesc();

    /** 게시 중인 버전. 동시에 게시가 겹쳐 둘이 남았더라도 나중에 게시한 쪽이 이긴다. */
    Optional<CatalogRecommendConfig> findFirstByActiveTrueOrderByPublishedAtDesc();

    /**
     * 게시를 모두 내린다(게시·게시 내리기). 한 문장 UPDATE 라 동시에 게시해도 InnoDB 행 잠금으로 줄을 서고 하나만 남는다.
     * 영속성 컨텍스트를 비워 이미 읽은 엔티티가 옛 active 를 들고 있지 않게 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CatalogRecommendConfig r set r.active = false where r.active = true")
    int deactivateAll();

    /** 이 버전 말고 그 파일(헤드·모델)을 쓰는 버전 수. 0 이면 지우거나 바꿀 때 파일도 지운다(복제한 버전은 같은 파일을 가리킨다). */
    @Query("select count(r) from CatalogRecommendConfig r where r.id <> :id and (r.head = :file or r.model = :file)")
    long countOtherUses(@Param("id") String id, @Param("file") CatalogImage file);
}
