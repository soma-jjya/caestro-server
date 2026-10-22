package com.caestro.server.domain.fill.dto.response;

import java.time.Instant;

/**
 * 대기열의 사진 하나: 칸에서 밀려난(바뀌거나 비워진) 사진. {@code fromSlot}은 있던 칸(0~3),
 * {@code clipUrl}은 그 사진의 클립(있을 때). 되돌리면 칸으로 돌아가고, 지우면 사라진다.
 */
public record FillQueueItemResponse(long id, String imageUrl, String clipUrl, int fromSlot, boolean filledByMe,
                                    String filledByName, Instant filledAt, Instant queuedAt) {
}
