package com.caestro.server.domain.support.event;

/** 운영자가 답한 순간. 커밋 뒤 알림함 + 푸시 {@code support_reply} 로 간다. */
public record SupportRepliedEvent(Long ticketId, Long userId, String title) {
}
