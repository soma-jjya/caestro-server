package com.caestro.server.domain.b2b.repository;

import com.caestro.server.domain.b2b.entity.B2bFrame;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface B2bFrameRepository extends JpaRepository<B2bFrame, String> {
    List<B2bFrame> findByProjectIdOrderByDisplayOrderAsc(String projectId);
}
