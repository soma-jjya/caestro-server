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

    // 만료 전날 안내 대상(#213): 창 (from, to] 안에 만료하고, 아직 안 보냈고, 사진이 한 장이라도 있는 세션. 주인은 같이 읽는다
    @Query("select s from FillSession s join fetch s.owner "
            + "where s.expiresAt > :from and s.expiresAt <= :to and s.expiringNotifiedAt is null "
            + "and exists (select sl.id from FillSlot sl where sl.session = s and sl.imageId is not null) "
            + "order by s.expiresAt")
    List<FillSession> findExpiringTargets(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // 만료 전날 안내를 세션당 한 번만: 아직 표시가 없을 때만 1(#213). 배치가 겹쳐 돌아도 한쪽만 이긴다
    @Modifying
    @Query("update FillSession s set s.expiringNotifiedAt = :now where s.id = :id and s.expiringNotifiedAt is null")
    int markExpiringNotified(@Param("id") Long id, @Param("now") LocalDateTime now);
}
