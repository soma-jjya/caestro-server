package com.caestro.server.domain.dailylimit.entity;

import com.caestro.server.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자별 일일 사용량 카운터.
 * 테이블 이름은 `shot_daily_limits`이지만, 특정 기능에 종속되지 않는 범용 일일 카운터로 사용한다.
 * (user_id, date) 조합당 1 row로 관리되며, 날짜가 바뀌면 자연스럽게 새 row로 카운트된다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "shot_daily_limits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_shot_daily_limits_user_date",
                columnNames = {"user_id", "date"}
        )
)
public class ShotDailyLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(nullable = false, columnDefinition = "TINYINT")
    private Integer count;

    /**
     * 사용량 카운트를 1 증가시킨다.
     */
    public void increment() {
        this.count += 1;
    }
}
