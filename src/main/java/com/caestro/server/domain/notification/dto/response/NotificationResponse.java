package com.caestro.server.domain.notification.dto.response;

import com.caestro.server.domain.notification.entity.Notification;
import java.time.LocalDateTime;
import java.util.Map;

/** 알림함의 한 줄. {@code data} 는 푸시의 data 와 같은 모양이라 앱이 같은 이동 규칙을 쓴다. */
public record NotificationResponse(
        Long id,
        String type,
        String title,
        String body,
        Map<String, String> data,
        LocalDateTime createdAt,
        LocalDateTime readAt
) {
    public static NotificationResponse from(Notification notification, Map<String, String> data) {
        return new NotificationResponse(notification.getId(), notification.getType(), notification.getTitle(),
                notification.getBody(), data, notification.getCreatedAt(), notification.getReadAt());
    }
}
