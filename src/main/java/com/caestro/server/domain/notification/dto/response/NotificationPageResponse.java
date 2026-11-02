package com.caestro.server.domain.notification.dto.response;

import java.util.List;

/** 최신순 한 페이지. {@code nextCursor} 가 null 이면 끝. 다음 요청에 {@code cursor} 로 그대로 넘긴다. */
public record NotificationPageResponse(List<NotificationResponse> items, Long nextCursor) {
}
