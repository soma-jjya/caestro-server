package com.caestro.server.domain.b2b.repository;

import com.caestro.server.domain.b2b.entity.B2bProject;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface B2bProjectRepository extends JpaRepository<B2bProject, String> {
    List<B2bProject> findAllByOrderByUpdatedAtDescIdAsc();
    Optional<B2bProject> findBySlug(String slug);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from B2bProject p where p.id = :id")
    Optional<B2bProject> findLockedById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from B2bProject p where p.slug = :slug")
    Optional<B2bProject> findLockedBySlug(@Param("slug") String slug);
}
