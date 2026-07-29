package com.caestro.server.domain.devicespec.entity;

import com.caestro.server.domain.session.entity.Session;
import com.caestro.server.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "device_specs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_device_specs_session_user",
                columnNames = {"session_id", "user_id"}
        )
)
public class DeviceSpec {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    // 스펙은 역할이 아니라 "기기(사람)"의 속성이므로 인증된 유저를 기준으로 저장한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(precision = 5, scale = 2)
    private BigDecimal maxZoom;

    @Column(precision = 5, scale = 2)
    private BigDecimal minZoom;

    @Column(precision = 7, scale = 4)
    private BigDecimal screenRatio;

    @Column(length = 20)
    private String maxResolution;

    @Column(length = 10)
    private String osType;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /**
     * 동일 세션·유저의 기기 스펙이 재수신되었을 때 카메라 스펙 값을 갱신한다.
     * 세션과 유저(user)는 식별자에 해당하므로 갱신 대상에서 제외한다.
     *
     * @param maxZoom       최대 줌 배율 (nullable)
     * @param minZoom       최소 줌 배율 (nullable)
     * @param screenRatio   화면 비율 (nullable)
     * @param maxResolution 지원 최대 해상도 (nullable)
     * @param osType        OS 종류 (nullable)
     */
    public void updateSpec(BigDecimal maxZoom, BigDecimal minZoom, BigDecimal screenRatio,
                           String maxResolution, String osType) {
        this.maxZoom = maxZoom;
        this.minZoom = minZoom;
        this.screenRatio = screenRatio;
        this.maxResolution = maxResolution;
        this.osType = osType;
    }
}
