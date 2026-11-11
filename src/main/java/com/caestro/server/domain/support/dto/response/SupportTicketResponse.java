package com.caestro.server.domain.support.dto.response;

import com.caestro.server.domain.support.entity.SupportTicket;
import com.caestro.server.domain.support.enums.SupportCategory;
import com.caestro.server.domain.support.enums.SupportStatus;

import java.time.LocalDateTime;
import java.util.List;

/** 앱의 문의 상세: 스레드 전체와 사진. */
public record SupportTicketResponse(
        Long id,
        SupportCategory category,
        String title,
        SupportStatus status,
        boolean userUnread,
        String appVersion,
        String platform,
        String osVersion,
        String deviceModel,
        LocalDateTime createdAt,
        LocalDateTime lastMessageAt,
        List<SupportMessageResponse> messages,
        List<SupportAttachmentResponse> attachments
) {
    /** @param ticketBaseUrl 사진 주소의 앞부분(예: {@code https://api.peakpic.app/support/tickets/12}) */
    public static SupportTicketResponse from(SupportTicket ticket, String ticketBaseUrl) {
        return new SupportTicketResponse(ticket.getId(), ticket.getCategory(), ticket.getTitle(), ticket.getStatus(),
                ticket.isUserUnread(), ticket.getAppVersion(), ticket.getPlatform(), ticket.getOsVersion(), ticket.getDeviceModel(),
                ticket.getCreatedAt(), ticket.getLastMessageAt(),
                ticket.getMessages().stream().map(SupportMessageResponse::from).toList(),
                ticket.getAttachments().stream().map(a -> SupportAttachmentResponse.from(a, ticketBaseUrl)).toList());
    }
}
