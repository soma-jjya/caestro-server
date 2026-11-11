package com.caestro.server.domain.support.dto.response;

import com.caestro.server.domain.support.entity.SupportMessage;
import com.caestro.server.domain.support.enums.SupportAuthor;

import java.time.LocalDateTime;

public record SupportMessageResponse(
        Long id,
        SupportAuthor author,
        String body,
        LocalDateTime createdAt
) {
    public static SupportMessageResponse from(SupportMessage message) {
        return new SupportMessageResponse(message.getId(), message.getAuthor(), message.getBody(), message.getCreatedAt());
    }
}
