package com.caestro.server.domain.shot.repository;

import com.caestro.server.domain.shot.entity.Shot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShotRepository extends JpaRepository<Shot, Long> {

    Page<Shot> findByDirectorId(Long directorId, Pageable pageable);

    // 협업 결과물은 두 참여자가 공동 소유이므로, director 또는 camera가 본인인 컷을 모두 반환한다.
    Page<Shot> findByDirectorIdOrCameraId(Long directorId, Long cameraId, Pageable pageable);
}
