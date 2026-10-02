package com.caestro.server.domain.fill.repository;

import com.caestro.server.domain.fill.entity.FillImage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FillImageRepository extends JpaRepository<FillImage, String> {
    Optional<FillImage> findByIdAndSessionId(String id, Long sessionId);
    void deleteBySessionId(Long sessionId);
}
