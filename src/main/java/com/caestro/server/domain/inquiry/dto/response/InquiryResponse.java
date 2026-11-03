package com.caestro.server.domain.inquiry.dto.response;

import com.caestro.server.domain.inquiry.entity.Inquiry;
import com.caestro.server.domain.inquiry.enums.InquiryStatus;
import com.caestro.server.domain.inquiry.enums.InquiryType;
import java.time.LocalDateTime;

/** 관리자 화면의 문의 한 건. 폼 값과 서버 흔적, 관리 필드를 모두 담는다. */
public record InquiryResponse(
        Long id,
        InquiryType type,
        String organization,
        String contactName,
        String phone,
        String email,
        String region,
        String preferredPeriod,
        String scale,
        String website,
        String message,
        boolean privacyAgreed,
        boolean marketingAgreed,
        Utm utm,
        String landingPath,
        String clientIp,
        String userAgent,
        InquiryStatus status,
        String adminMemo,
        String linkedProjectId,
        LocalDateTime handledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public record Utm(String source, String medium, String campaign, String content, String term) {
    }

    public static InquiryResponse from(Inquiry i) {
        return new InquiryResponse(i.getId(), i.getType(), i.getOrganization(), i.getContactName(), i.getPhone(),
                i.getEmail(), i.getRegion(), i.getPreferredPeriod(), i.getScale(), i.getWebsite(), i.getMessage(),
                i.isPrivacyAgreed(), i.isMarketingAgreed(),
                new Utm(i.getUtmSource(), i.getUtmMedium(), i.getUtmCampaign(), i.getUtmContent(), i.getUtmTerm()),
                i.getLandingPath(), i.getClientIp(), i.getUserAgent(), i.getStatus(), i.getAdminMemo(),
                i.getLinkedProjectId(), i.getHandledAt(), i.getCreatedAt(), i.getUpdatedAt());
    }
}
