package com.caestro.server.domain.shot.repository;

import com.caestro.server.domain.shot.entity.Shot;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShotRepository extends JpaRepository<Shot, Long> {

    Page<Shot> findByDirectorId(Long directorId, Pageable pageable);

    // 협업 결과물은 두 참여자가 공동 소유이므로, director 또는 camera가 본인인 컷을 모두 반환한다.
    Page<Shot> findByDirectorIdOrCameraId(Long directorId, Long cameraId, Pageable pageable);

    // 만료 데이터 정리 배치: 세션은 삭제하되 shot(사용자 콘텐츠)은 보존하기 위해 session 참조만 끊는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Shot sh SET sh.session = null WHERE sh.session.id IN "
            + "(SELECT s.id FROM Session s WHERE s.createdAt < :cutoff)")
    int detachFromSessionsCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
