package com.caestro.server.domain.support.dto.response;

import com.caestro.server.domain.support.entity.SupportAttachment;

import java.time.LocalDateTime;

/** 사진 한 장. {@code url} 은 부르는 쪽(앱·관리자)에 맞는 경로로 만들어 준다 — 둘 다 인증이 필요하다. */
public record SupportAttachmentResponse(
        Long id,
        String url,
        String contentType,
        int sizeBytes,
        LocalDateTime createdAt
) {
    public static SupportAttachmentResponse from(SupportAttachment attachment, String ticketBaseUrl) {
        return new SupportAttachmentResponse(attachment.getId(), ticketBaseUrl + "/attachments/" + attachment.getId(),
                attachment.getContentType(), attachment.getSizeBytes(), attachment.getCreatedAt());
    }
}
