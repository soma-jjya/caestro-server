package com.caestro.server.domain.push.dto.response;

import com.caestro.server.domain.push.entity.PushToken;
import com.caestro.server.domain.push.enums.PushPlatform;
import java.time.LocalDateTime;

public record PushTokenResponse(
        Long id,
        PushPlatform platform,
        LocalDateTime createdAt,
        LocalDateTime lastSeenAt
) {
    public static PushTokenResponse from(PushToken token) {
        return new PushTokenResponse(token.getId(), token.getPlatform(), token.getCreatedAt(), token.getLastSeenAt());
    }
}
