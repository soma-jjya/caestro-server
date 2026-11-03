package com.caestro.server.domain.inquiry.entity;

import com.caestro.server.domain.inquiry.enums.InquiryStatus;
import com.caestro.server.domain.inquiry.enums.InquiryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * B2B 문의 한 건 (#216, contracts/b2b-inquiries.md). 광고 랜딩의 폼이 남기고 관리자 페이지가 읽는다.
 * utm 다섯 값은 "어느 소재로 들어온 문의가 계약이 됐나"를 SQL 로 세기 위해 JSON 이 아니라 컬럼으로 둔다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "b2b_inquiries", indexes = {
        @Index(name = "idx_b2b_inquiries_status", columnList = "status, id"),
        @Index(name = "idx_b2b_inquiries_type", columnList = "type, id"),
        @Index(name = "idx_b2b_inquiries_created", columnList = "created_at")
})
public class Inquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InquiryType type;

    @Column(nullable = false, length = 80)
    private String organization;

    @Column(nullable = false, length = 40)
    private String contactName;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(length = 120)
    private String email;

    @Column(length = 40)
    private String region;

    @Column(length = 80)
    private String preferredPeriod;

    @Column(length = 80)
    private String scale;

    @Column(length = 200)
    private String website;

    @Column(length = 500)
    private String message;

    @Column(nullable = false)
    private boolean privacyAgreed;

    @Column(nullable = false)
    private boolean marketingAgreed;

    @Column(length = 100)
    private String utmSource;

    @Column(length = 100)
    private String utmMedium;

    @Column(length = 100)
    private String utmCampaign;

    @Column(length = 100)
    private String utmContent;

    @Column(length = 100)
    private String utmTerm;

    @Column(length = 200)
    private String landingPath;

    // 서버가 채우는 흔적. 스팸 판단과 한도 교정에 쓴다
    @Column(length = 45)
    private String clientIp;

    @Column(length = 300)
    private String userAgent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InquiryStatus status;

    @Column(length = 2000)
    private String adminMemo;

    // 계약 뒤 만든 B2B 프로젝트(b2b_projects.id). 소재별 "문의 → 계약" 전환을 세는 고리
    @Column(length = 40)
    private String linkedProjectId;

    // NEW 를 처음 벗어난 시각. 응답 속도를 보는 값이라 되돌려도 지우지 않는다
    private LocalDateTime handledAt;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    /** 상태 변경. NEW 에서 벗어나는 첫 순간만 handledAt 을 남긴다. */
    public void changeStatus(InquiryStatus next, LocalDateTime now) {
        if (handledAt == null && next != InquiryStatus.NEW) {
            handledAt = now;
        }
        status = next;
    }

    public void changeMemo(String memo) {
        adminMemo = memo;
    }

    public void linkProject(String projectId) {
        linkedProjectId = projectId;
    }
}
