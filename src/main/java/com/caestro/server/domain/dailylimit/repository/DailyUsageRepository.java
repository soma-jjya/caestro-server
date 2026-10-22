package com.caestro.server.domain.dailylimit.repository;

import com.caestro.server.domain.dailylimit.entity.DailyUsage;
import com.caestro.server.domain.dailylimit.enums.DailyAction;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyUsageRepository extends JpaRepository<DailyUsage, Long> {

    long countByUserIdAndUsageDateAndActionType(Long userId, LocalDate usageDate, DailyAction actionType);

    boolean existsByUserIdAndUsageDateAndActionTypeAndUsageKey(Long userId, LocalDate usageDate,
                                                               DailyAction actionType, String usageKey);

    /** 정리 배치: 기준일보다 오래된 기록을 지운다. */
    @Modifying
    @Query("delete from DailyUsage u where u.usageDate < :before")
    int deleteOlderThan(@Param("before") LocalDate before);
}
