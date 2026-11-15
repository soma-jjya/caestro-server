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

    /**
     * 되찾기: lease 가 지난 PROCESSING(죽은 릴레이가 쥔 것)과 RELAYED(큐에 넣었지만 소비자가 끝내지 못한 채 큐에서 사라진 것, #273)를 PENDING 으로.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = com.caestro.server.domain.notification.entity.OutboxStatus.PENDING, o.claimToken = null, o.leaseUntil = null
             where o.status in (com.caestro.server.domain.notification.entity.OutboxStatus.PROCESSING,
                                com.caestro.server.domain.notification.entity.OutboxStatus.RELAYED)
               and o.leaseUntil < :now
            """)
    int releaseStale(@Param("now") LocalDateTime now);

    /**
     * 집은 행 중 대기 중인 묶음 행(dedup_key 가 {@code …:open})의 키를 {@code …:open#id} 로 바꿔 자리를 비운다(#274).
     * 그 뒤의 사건은 이 행에 합쳐지지 않고 다음 묶음의 새 행이 된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationOutbox o set o.dedupKey = concat(o.dedupKey, '#', cast(o.id as string)) where o.claimToken = :token and o.dedupKey like '%:open'")
    int releaseOpenKeys(@Param("token") String token);

    /**
     * 묶음 알림 넣기(#274, MySQL). 대기 중인 행이 없으면 새로 넣고, 있으면 합친다: 보낼 시각을 [notBefore](마지막 사건 + 조용한 시간)로 늦추되
     * 처음 넣은 시각 + [windowSeconds] 를 넘기지 않고, 앞당기지도 않는다. 동시에 두 사건이 와도 dedup_key 유니크가 한 행으로 모은다.
     * merged_count 를 늘리므로 합치면 항상 행이 바뀐다 — 돌려받는 수가 1 이면 새 행, 2 면 합침.
     */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            insert into notification_outbox
                   (user_id, type, title, body, data, dedup_key, collapse_key, not_before, status, attempts, merged_count, created_at)
            values (:userId, :type, :title, :body, :data, :dedupKey, :collapseKey, :notBefore, 'PENDING', 0, 1, :now)
            on duplicate key update
                   merged_count = merged_count + 1,
                   not_before = greatest(not_before, least(:notBefore, created_at + interval :windowSeconds second))
            """)
    int upsertCollapsed(@Param("userId") Long userId, @Param("type") String type, @Param("title") String title, @Param("body") String body,
                        @Param("data") String data, @Param("dedupKey") String dedupKey, @Param("collapseKey") String collapseKey,
                        @Param("notBefore") LocalDateTime notBefore, @Param("now") LocalDateTime now, @Param("windowSeconds") long windowSeconds);

    /** 이 묶음에서 마지막으로 보낸 시각(#274). 보낸 뒤 [window] 안에는 다음 묶음을 보내지 않는다. */
    @Query("select max(o.sentAt) from NotificationOutbox o where o.collapseKey = :collapseKey and o.status = com.caestro.server.domain.notification.entity.OutboxStatus.SENT")
    LocalDateTime lastSentAt(@Param("collapseKey") String collapseKey);

    /** 대기 중인 묶음 행을 취소(#274). 키도 비워 다음 묶음이 새로 생길 수 있게. @return 취소한 행 수 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = com.caestro.server.domain.notification.entity.OutboxStatus.CANCELLED, o.sentAt = :now,
                   o.dedupKey = concat(o.dedupKey, '#', cast(o.id as string))
             where o.collapseKey = :collapseKey and o.status = com.caestro.server.domain.notification.entity.OutboxStatus.PENDING
            """)
    int cancelPending(@Param("collapseKey") String collapseKey, @Param("now") LocalDateTime now);

    List<NotificationOutbox> findAllByCollapseKeyOrderByIdAsc(String collapseKey);

    long countByStatus(OutboxStatus status);

    /** 가장 오래 기다리는 PENDING 행의 생성 시각(지표: 적체 나이). 없으면 null. */
    @Query("select min(o.createdAt) from NotificationOutbox o where o.status = com.caestro.server.domain.notification.entity.OutboxStatus.PENDING")
    LocalDateTime oldestPendingCreatedAt();

    /** 끝난 행 정리(보관 기간 뒤). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from NotificationOutbox o where o.status in :statuses and o.sentAt < :before")
    int deleteFinishedBefore(@Param("statuses") Collection<OutboxStatus> statuses, @Param("before") LocalDateTime before);
}
