package com.caestro.server.domain.fill.repository;

import com.caestro.server.domain.fill.entity.FillSlot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FillSlotRepository extends JpaRepository<FillSlot, Long> {
    List<FillSlot> findBySessionIdOrderBySlotIndex(Long sessionId);
    Optional<FillSlot> findBySessionIdAndSlotIndex(Long sessionId, int slotIndex);
    void deleteBySessionId(Long sessionId);
}
