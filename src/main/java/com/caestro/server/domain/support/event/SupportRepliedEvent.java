package com.caestro.server.domain.support.event;

/** 운영자가 답한 순간. 같은 트랜잭션에서 아웃박스 행이 되어(#272) 알림함 + 푸시 {@code support_reply} 로 간다. {@code messageNo} 는 그 티켓의 몇 번째 메시지인지(중복 방지 키). */
public record SupportRepliedEvent(Long ticketId, Long userId, String title, int messageNo) {
}
