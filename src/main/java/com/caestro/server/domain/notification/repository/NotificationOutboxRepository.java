package com.caestro.server.domain.notification.repository;

import com.caestro.server.domain.notification.entity.NotificationOutbox;
import com.caestro.server.domain.notification.entity.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    boolean existsByDedupKey(String dedupKey);

    Optional<NotificationOutbox> findByDedupKey(String dedupKey);

    /** 보낼 차례인 행(오래된 것부터). 잠그지 않고 읽는다 — 집는 것은 {@link #claim} 이 한다. */
    @Query("select o from NotificationOutbox o where o.status = :status and o.notBefore <= :now order by o.id asc")
    List<NotificationOutbox> findDue(@Param("status") OutboxStatus status, @Param("now") LocalDateTime now, Pageable pageable);

    /**
     * 집기: PENDING 인 행만 PROCESSING 으로 바꾼다. 같은 id 를 두 릴레이가 동시에 고르면 UPDATE 가 행 단위로 한 쪽만 이긴다(DB 무관).
     * 이긴 쪽은 {@link #findClaimed} 로 자기 토큰의 행을 다시 읽는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = com.caestro.server.domain.notification.entity.OutboxStatus.PROCESSING,
                   o.claimToken = :token, o.leaseUntil = :leaseUntil
             where o.id in :ids and o.status = com.caestro.server.domain.notification.entity.OutboxStatus.PENDING
            """)
    int claim(@Param("ids") Collection<Long> ids, @Param("token") String token, @Param("leaseUntil") LocalDateTime leaseUntil);

    List<NotificationOutbox> findAllByClaimTokenAndStatusOrderByIdAsc(String claimToken, OutboxStatus status);

    /** 죽은 릴레이가 쥐고 있던 행 되찾기: lease 가 지난 PROCESSING 을 PENDING 으로. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = com.caestro.server.domain.notification.entity.OutboxStatus.PENDING, o.claimToken = null, o.leaseUntil = null
             where o.status = com.caestro.server.domain.notification.entity.OutboxStatus.PROCESSING and o.leaseUntil < :now
            """)
    int releaseStale(@Param("now") LocalDateTime now);

    long countByStatus(OutboxStatus status);

    /** 가장 오래 기다리는 PENDING 행의 생성 시각(지표: 적체 나이). 없으면 null. */
    @Query("select min(o.createdAt) from NotificationOutbox o where o.status = com.caestro.server.domain.notification.entity.OutboxStatus.PENDING")
    LocalDateTime oldestPendingCreatedAt();

    /** 끝난 행 정리(보관 기간 뒤). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from NotificationOutbox o where o.status in :statuses and o.sentAt < :before")
    int deleteFinishedBefore(@Param("statuses") Collection<OutboxStatus> statuses, @Param("before") LocalDateTime before);
}
