package com.caestro.server.domain.support.repository;

import com.caestro.server.domain.support.entity.SupportTicket;
import com.caestro.server.domain.support.enums.SupportCategory;
import com.caestro.server.domain.support.enums.SupportStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    /** 본인 것만. 남의 id 는 없는 것과 같다(404). */
    Optional<SupportTicket> findByIdAndUserId(Long id, Long userId);

    /** 내 문의 최신순 한 페이지(알림함과 같은 id 커서). */
    @Query("""
            select t from SupportTicket t
            where t.user.id = :userId
              and (:cursor is null or t.id < :cursor)
            order by t.id desc
            """)
    List<SupportTicket> findPageByUser(@Param("userId") Long userId, @Param("cursor") Long cursor, Pageable pageable);

    /** 관리자 목록. 필터·커서는 null 이면 조건에서 빠진다. */
    @Query("""
            select t from SupportTicket t
            where (:status is null or t.status = :status)
              and (:category is null or t.category = :category)
              and (:cursor is null or t.id < :cursor)
            order by t.id desc
            """)
    List<SupportTicket> findPage(@Param("status") SupportStatus status, @Param("category") SupportCategory category,
                                 @Param("cursor") Long cursor, Pageable pageable);

    long countByStatus(SupportStatus status);

    /** 보관 기간이 지난 것(마지막 메시지 기준)을 id 순으로 한 묶음. 정리 배치가 지우다 실패한 행을 되풀이하지 않게 id 커서로 넘긴다. */
    List<SupportTicket> findByLastMessageAtBeforeAndIdGreaterThanOrderByIdAsc(LocalDateTime cutoff, Long afterId, Pageable pageable);
}
