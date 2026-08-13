package com.caestro.server.domain.devicespec.repository;

import com.caestro.server.domain.devicespec.entity.DeviceSpec;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceSpecRepository extends JpaRepository<DeviceSpec, Long> {

    List<DeviceSpec> findBySessionId(Long sessionId);

    Optional<DeviceSpec> findBySessionIdAndUserId(Long sessionId, Long userId);

    // 만료 데이터 정리 배치: 생성 cutoff 이전 세션에 속한 기기 스펙을 먼저 삭제한다.
    // (session_id가 NOT NULL이므로 세션 삭제 전에 반드시 선행되어야 한다)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM DeviceSpec d WHERE d.session.id IN "
            + "(SELECT s.id FROM Session s WHERE s.createdAt < :cutoff)")
    int deleteBySessionCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
