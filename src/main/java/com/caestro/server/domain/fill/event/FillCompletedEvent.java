package com.caestro.server.domain.fill.event;

/**
 * 함께 채우기 네 칸이 처음으로 모두 찬 순간 (#206). 세션당 한 번만 발행된다(FillService.notifyIfCompleted).
 * 커밋 뒤에만 처리된다(@TransactionalEventListener AFTER_COMMIT) — 알림은 왔는데 칸이 비어 있는 일이 없도록.
 */
public record FillCompletedEvent(Long sessionId, Long ownerId, String code) {
}
