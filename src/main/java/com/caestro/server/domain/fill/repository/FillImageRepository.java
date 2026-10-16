package com.caestro.server.domain.fill.repository;

import com.caestro.server.domain.fill.entity.FillImage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FillImageRepository extends JpaRepository<FillImage, String> {
    Optional<FillImage> findByIdAndSessionId(String id, Long sessionId);
    // 세션에 남은 사진 수 — 고아 이미지 검증과 용량 점검에 쓴다
    long countBySessionId(Long sessionId);
    void deleteBySessionId(Long sessionId);
}
