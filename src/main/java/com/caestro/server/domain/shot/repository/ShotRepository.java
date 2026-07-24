package com.caestro.server.domain.shot.repository;

import com.caestro.server.domain.shot.entity.Shot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShotRepository extends JpaRepository<Shot, Long> {

    Page<Shot> findByDirectorId(Long directorId, Pageable pageable);
}
