package com.caestro.server.domain.b2b.repository;

import com.caestro.server.domain.b2b.entity.B2bFill;
import com.caestro.server.domain.fill.entity.FillSession;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface B2bFillRepository extends JpaRepository<B2bFill, String> {
    Optional<B2bFill> findByProjectSlugAndSessionCode(String slug, String code);

    @Query("select distinct f.image.id from B2bFill f where f.project.id = :projectId")
    List<String> findImageIdsByProjectId(@Param("projectId") String projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FillSession s where s.id in (select f.session.id from B2bFill f where f.project.id = :projectId) order by s.id")
    List<FillSession> findLockedSessionsByProjectId(@Param("projectId") String projectId);

    @Modifying
    @Query("delete from B2bFill f where f.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
