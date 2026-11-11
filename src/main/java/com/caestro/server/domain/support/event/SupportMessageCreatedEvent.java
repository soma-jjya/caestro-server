package com.caestro.server.domain.support.event;

/** 사용자가 이어 쓴 순간(운영자 답변은 {@link SupportRepliedEvent}). 웹훅으로 운영자를 다시 부른다. */
public record SupportMessageCreatedEvent(Long ticketId, String title) {
}
