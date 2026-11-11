package com.caestro.server.domain.support.dto.response;

import com.caestro.server.domain.support.entity.SupportTicket;
import com.caestro.server.domain.support.enums.SupportCategory;
import com.caestro.server.domain.support.enums.SupportStatus;
import com.caestro.server.domain.user.entity.User;

import java.time.LocalDateTime;
import java.util.List;

/** 관리자의 문의 상세: 앱 상세 + 누가 보냈는지(탈퇴했으면 익명)·메모·응답 시각. */
public record SupportAdminTicketResponse(
        Long id,
        SupportCategory category,
        String title,
        SupportStatus status,
        boolean userUnread,
        String appVersion,
        String platform,
        String osVersion,
        String deviceModel,
        String adminMemo,
        LocalDateTime createdAt,
        LocalDateTime lastMessageAt,
        LocalDateTime firstAnsweredAt,
        LocalDateTime closedAt,
        Requester user,
        List<SupportMessageResponse> messages,
        List<SupportAttachmentResponse> attachments
) {
    /** 보낸 사람. 탈퇴한 계정은 {@code withdrawn=true} 에 닉네임·가입 방법이 비어 있다(User.withdraw 가 익명화한 값). */
    public record Requester(Long id, String nickname, String provider, boolean withdrawn) {
        static Requester from(User user) {
            return new Requester(user.getId(), user.getNickname(), user.getOauthProvider(), user.isDeleted());
        }
    }

    public static SupportAdminTicketResponse from(SupportTicket ticket, String ticketBaseUrl) {
        return new SupportAdminTicketResponse(ticket.getId(), ticket.getCategory(), ticket.getTitle(), ticket.getStatus(),
                ticket.isUserUnread(), ticket.getAppVersion(), ticket.getPlatform(), ticket.getOsVersion(), ticket.getDeviceModel(),
                ticket.getAdminMemo(), ticket.getCreatedAt(), ticket.getLastMessageAt(), ticket.getFirstAnsweredAt(), ticket.getClosedAt(),
                Requester.from(ticket.getUser()),
                ticket.getMessages().stream().map(SupportMessageResponse::from).toList(),
                ticket.getAttachments().stream().map(a -> SupportAttachmentResponse.from(a, ticketBaseUrl)).toList());
    }
}
