package com.caestro.server.domain.dailylimit.entity;

import com.caestro.server.domain.dailylimit.enums.DailyAction;
import com.caestro.server.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 무료 사용자의 하루 사용 기록. 한 번 쓸 때마다 한 행이다.
 *
 * <ul>
 *   <li>(user, date, action, usage_key) 유니크: 같은 네컷을 다시 저장하거나 앱이 재시도해도 한 번만 센다.</li>
 *   <li>(user, date, action, slot) 유니크: slot 은 1..한도. 동시에 저장해도 한도보다 많은 행이 생기지 않는다(잠금 없이).</li>
 * </ul>
 * 기존 shot_daily_limits(사용자·날짜당 숫자 하나)는 동작을 구분하지 못하고 재시도에 두 번 세어져 쓰지 않는다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "daily_usages",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_daily_usages_key", columnNames = {"user_id", "usage_date", "action_type", "usage_key"}),
                @UniqueConstraint(name = "uk_daily_usages_slot", columnNames = {"user_id", "usage_date", "action_type", "slot"})
        },
        indexes = @Index(name = "idx_daily_usages_date", columnList = "usage_date")
)
public class DailyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 한도를 세는 날 (limits.zone 기준, 기본 Asia/Seoul 자정에 바뀐다) */
    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    /** 동작이 늘어도 ALTER 가 필요 없도록 MySQL ENUM 이 아닌 VARCHAR */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "action_type", nullable = false, length = 30)
    private DailyAction actionType;

    /** 그날 몇 번째 사용인가 (1..한도) */
    @Column(nullable = false)
    private Integer slot;

    /** 같은 것을 한 번만 세기 위한 클라이언트 키 (네컷이면 앱의 네컷 id) */
    @Column(name = "usage_key", nullable = false, length = 64)
    private String usageKey;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
