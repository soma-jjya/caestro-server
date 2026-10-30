package com.caestro.server.domain.b2b.repository;

import com.caestro.server.domain.b2b.entity.B2bFrame;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface B2bFrameRepository extends JpaRepository<B2bFrame, String> {
    List<B2bFrame> findByProjectIdOrderByDisplayOrderAsc(String projectId);

    @Query("select distinct f.image.id from B2bFrame f where f.project.id = :projectId and f.image is not null")
    List<String> findImageIdsByProjectId(@Param("projectId") String projectId);

    @Modifying
    @Query("delete from B2bFrame f where f.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
