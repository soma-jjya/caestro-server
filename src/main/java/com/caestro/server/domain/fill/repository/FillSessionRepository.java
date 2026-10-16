package com.caestro.server.domain.fill.repository;

import com.caestro.server.domain.fill.entity.FillSession;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FillSessionRepository extends JpaRepository<FillSession, Long> {
    Optional<FillSession> findByCode(String code);
    boolean existsByCode(String code);
    List<FillSession> findByExpiresAtBefore(LocalDateTime cutoff);
    // 사용자당 활성 세션 상한(#150)용. owner_id FK 인덱스를 탄다
    long countByOwnerIdAndExpiresAtAfter(Long ownerId, LocalDateTime now);
}
