package com.caestro.server.domain.dailylimit.repository;

import com.caestro.server.domain.dailylimit.entity.ShotDailyLimit;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShotDailyLimitRepository extends JpaRepository<ShotDailyLimit, Long> {

    Optional<ShotDailyLimit> findByUserIdAndDate(Long userId, LocalDate date);
}
