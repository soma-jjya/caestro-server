package com.caestro.server.domain.b2b.repository;

import com.caestro.server.domain.b2b.entity.B2bFill;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface B2bFillRepository extends JpaRepository<B2bFill, String> {
    Optional<B2bFill> findByProjectSlugAndSessionCode(String slug, String code);
}
