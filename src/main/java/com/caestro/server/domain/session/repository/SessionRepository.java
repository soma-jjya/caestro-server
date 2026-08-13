package com.caestro.server.domain.session.repository;

import com.caestro.server.domain.session.entity.Session;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, Long> {

    Optional<Session> findBySessionCode(String sessionCode);

    // 만료 데이터 정리 배치: 생성 cutoff 이전 세션을 삭제한다.
    // (종속 데이터인 device_specs 삭제, shots 참조 해제 이후 마지막에 실행되어야 한다)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Session s WHERE s.createdAt < :cutoff")
    int deleteByCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
