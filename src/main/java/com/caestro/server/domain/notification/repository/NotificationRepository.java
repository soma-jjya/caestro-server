package com.caestro.server.domain.notification.repository;

import com.caestro.server.domain.notification.entity.Notification;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 첫 페이지: 최신순. 커서는 id 로 잡는다(IDENTITY 라 생성 순서와 같다). */
    List<Notification> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /** 다음 페이지: 커서(마지막으로 본 id)보다 오래된 것. */
    List<Notification> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long id, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    /** 본인 것만. 남의 알림은 없는 것과 같다. */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    @Modifying(clearAutomatically = true)
    @Query("update Notification n set n.readAt = :now where n.user.id = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("delete from Notification n where n.createdAt < :before")
    int deleteCreatedBefore(@Param("before") LocalDateTime before);
}
