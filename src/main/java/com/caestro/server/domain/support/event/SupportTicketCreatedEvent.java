package com.caestro.server.domain.support.event;

import com.caestro.server.domain.support.enums.SupportCategory;

/** 문의가 저장된 순간. 커밋 뒤에만 처리된다(웹훅 링크가 관리자 페이지에 없는 일이 없도록). 개인정보는 싣지 않는다. */
public record SupportTicketCreatedEvent(Long ticketId, SupportCategory category, String title, String platform, String appVersion) {
}
