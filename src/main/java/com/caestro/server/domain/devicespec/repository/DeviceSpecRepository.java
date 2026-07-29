package com.caestro.server.domain.devicespec.repository;

import com.caestro.server.domain.devicespec.entity.DeviceSpec;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceSpecRepository extends JpaRepository<DeviceSpec, Long> {

    List<DeviceSpec> findBySessionId(Long sessionId);

    Optional<DeviceSpec> findBySessionIdAndUserId(Long sessionId, Long userId);
}
