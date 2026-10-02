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
}
