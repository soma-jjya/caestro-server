package com.caestro.server.domain.fill.repository;

import com.caestro.server.domain.fill.entity.FillQueueItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FillQueueRepository extends JpaRepository<FillQueueItem, Long> {
    /** 대기열 순서: 먼저 밀려난 것이 위, 방금 밀려난 것이 맨 아래. */
    List<FillQueueItem> findBySessionIdOrderByIdAsc(Long sessionId);

    Optional<FillQueueItem> findByIdAndSessionId(Long id, Long sessionId);

    void deleteBySessionId(Long sessionId);
}
