package com.caestro.server.domain.fill.repository;

import com.caestro.server.domain.fill.entity.FillSlot;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface FillSlotRepository extends JpaRepository<FillSlot, Long> {
    List<FillSlot> findBySessionIdOrderBySlotIndex(Long sessionId);

    /**
     * 칸 하나를 행 잠금(SELECT ... FOR UPDATE)으로 읽는다 (#151).
     * 같은 칸에 동시 요청이 오면 뒤 트랜잭션은 여기서 기다렸다가 앞이 커밋한 결과를 읽는다 —
     * "비었나 확인"과 "내 것으로 기록" 사이에 끼어들 틈이 없어진다. 다른 칸은 다른 행이라 서로 막지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FillSlot> findBySessionIdAndSlotIndex(Long sessionId, int slotIndex);

    void deleteBySessionId(Long sessionId);
}
