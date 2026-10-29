package com.caestro.server.domain.fill.repository;

import com.caestro.server.domain.fill.entity.FillSession;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FillSessionRepository extends JpaRepository<FillSession, Long> {
    Optional<FillSession> findByCode(String code);
    boolean existsByCode(String code);
    List<FillSession> findByExpiresAtBefore(LocalDateTime cutoff);
    // 사용자당 활성 세션 상한(#150)용. owner_id FK 인덱스를 탄다
    long countByOwnerIdAndExpiresAtAfter(Long ownerId, LocalDateTime now);

    // 네 칸 완성 알림을 세션당 한 번만: 아직 표시가 없을 때만 1을 돌려준다(#206). 동시 요청은 여기서 한쪽만 이긴다
    @Modifying
    @Query("update FillSession s set s.completedNotifiedAt = :now where s.id = :id and s.completedNotifiedAt is null")
    int markCompletedNotified(@Param("id") Long id, @Param("now") LocalDateTime now);
}
