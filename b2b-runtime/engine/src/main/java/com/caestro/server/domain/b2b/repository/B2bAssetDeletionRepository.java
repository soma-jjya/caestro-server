package com.caestro.server.domain.b2b.repository;

import com.caestro.server.domain.b2b.entity.B2bAssetDeletion;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface B2bAssetDeletionRepository extends JpaRepository<B2bAssetDeletion, String> {
    List<B2bAssetDeletion> findTop100ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from B2bAssetDeletion d where d.id = :id")
    Optional<B2bAssetDeletion> findLockedById(@Param("id") String id);
}
