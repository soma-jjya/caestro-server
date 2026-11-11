package com.caestro.server.domain.support.dto.response;

import com.caestro.server.domain.support.entity.SupportTicket;
import com.caestro.server.domain.support.enums.SupportCategory;
import com.caestro.server.domain.support.enums.SupportStatus;

import java.time.LocalDateTime;

/** 목록 한 줄(앱의 내 문의, 관리자 목록 공통). 메시지·사진은 상세에서. */
public record SupportTicketSummaryResponse(
        Long id,
        SupportCategory category,
        String title,
        SupportStatus status,
        boolean userUnread,
        String platform,
        String appVersion,
        LocalDateTime createdAt,
        LocalDateTime lastMessageAt
) {
    public static SupportTicketSummaryResponse from(SupportTicket ticket) {
        return new SupportTicketSummaryResponse(ticket.getId(), ticket.getCategory(), ticket.getTitle(), ticket.getStatus(),
                ticket.isUserUnread(), ticket.getPlatform(), ticket.getAppVersion(), ticket.getCreatedAt(), ticket.getLastMessageAt());
    }
}
