package com.caestro.server.domain.inquiry.repository;

import com.caestro.server.domain.inquiry.entity.Inquiry;
import com.caestro.server.domain.inquiry.enums.InquiryStatus;
import com.caestro.server.domain.inquiry.enums.InquiryType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    /** 최신순 한 페이지. 필터·커서는 null 이면 조건에서 빠진다(알림함과 같은 id 커서, IDENTITY 라 생성 순서와 같다). */
    @Query("""
            select i from Inquiry i
            where (:status is null or i.status = :status)
              and (:type is null or i.type = :type)
              and (:cursor is null or i.id < :cursor)
            order by i.id desc
            """)
    List<Inquiry> findPage(@Param("status") InquiryStatus status, @Param("type") InquiryType type,
                           @Param("cursor") Long cursor, Pageable pageable);

    long countByStatus(InquiryStatus status);
}
