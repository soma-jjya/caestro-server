package com.caestro.server.domain.fill.event;

import java.util.List;

/**
 * 함께 채우기 네 칸이 처음으로 모두 찬 순간 (#206). 세션당 한 번만 발행된다(FillService.notifyIfCompleted).
 * 커밋 뒤에만 처리된다(@TransactionalEventListener AFTER_COMMIT) — 알림은 왔는데 칸이 비어 있는 일이 없도록.
 *
 * @param participantIds 그 순간 네 칸에 사진을 넣은 사람들. 주인은 빼고, 두 칸을 채운 사람도 한 번만. 대기열로 밀려난 사진의 주인은
 *                       완성된 네컷에 없으니 들지 않는다
 */
public record FillCompletedEvent(Long sessionId, Long ownerId, String code, List<Long> participantIds) {

    public FillCompletedEvent {
        participantIds = List.copyOf(participantIds);
    }
}
