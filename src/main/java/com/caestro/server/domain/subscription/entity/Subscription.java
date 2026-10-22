package com.caestro.server.domain.subscription.entity;

import com.caestro.server.domain.subscription.enums.SubscriptionPlan;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "subscriptions",
        uniqueConstraints = @UniqueConstraint(name = "uk_subscriptions_purchase_token", columnNames = "purchase_token")
)
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    // ---- 스토어 결제(contracts/subscription-google-play.md). 수동 부여(MANUAL) 행은 아래가 전부 비어 있다 ----

    /** 결제가 일어난 곳. 새 값이 늘어도 ALTER 가 필요 없도록 MySQL ENUM 이 아닌 VARCHAR 로 둔다. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private SubscriptionStore store;

    /** Google Play 구매 토큰. 한 토큰은 한 계정에만 묶인다(유니크). */
    @Column(length = 512)
    private String purchaseToken;

    /** 스토어 상품 ID (peakpic_pro) */
    @Column(length = 100)
    private String productId;

    /** 기본 요금제 ID (yearly · monthly · weekly) */
    @Column(length = 40)
    private String basePlanId;

    /** 스토어가 알려 준 원래 상태 (SUBSCRIPTION_STATE_ACTIVE 등). status 는 이것을 권한 기준으로 줄인 값 */
    @Column(length = 50)
    private String storeState;

    /** 다음 결제일에 자동 갱신되는지. false 면 해지 예약(만료일까지는 이용 가능) */
    private Boolean autoRenewing;

    /** 마지막 주문 번호 (GPA.xxxx). 환불·문의 대조용 */
    @Column(length = 100)
    private String latestOrderId;

    /**
     * 스토어에서 다시 받아 온 상태로 덮어쓴다. 사용자·토큰은 바뀌지 않는다.
     */
    public void applyStoreState(SubscriptionStatus status, String storeState, String productId, String basePlanId,
                                LocalDateTime startedAt, LocalDateTime expiresAt, Boolean autoRenewing,
                                String latestOrderId) {
        this.status = status;
        this.storeState = storeState;
        this.productId = productId;
        if (basePlanId != null) {
            this.basePlanId = basePlanId;
        }
        if (startedAt != null) {
            this.startedAt = startedAt;
        }
        this.expiresAt = expiresAt;
        this.autoRenewing = autoRenewing;
        if (latestOrderId != null) {
            this.latestOrderId = latestOrderId;
        }
    }

    /**
     * 요금제 변경·재구독으로 새 토큰이 이 구매를 대신하게 됐다. 권한은 새 행이 이어받는다.
     */
    public void markReplaced(LocalDateTime now) {
        this.status = SubscriptionStatus.EXPIRED;
        this.storeState = "REPLACED";
        this.autoRenewing = false;
        if (this.expiresAt.isAfter(now)) {
            this.expiresAt = now;
        }
    }

    /**
     * 환불·취소(voided)로 결제가 무효가 됐다. 남은 기간과 관계없이 바로 끝낸다.
     */
    public void revoke(LocalDateTime now) {
        this.status = SubscriptionStatus.CANCELED;
        this.storeState = "REVOKED";
        this.autoRenewing = false;
        if (this.expiresAt.isAfter(now)) {
            this.expiresAt = now;
        }
    }
}
