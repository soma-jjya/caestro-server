package com.caestro.server.domain.fill.dto.response;

import java.time.Instant;

/** clipUrl: 사진과 함께 찍힌 움직이는 GIF(앱 안 카메라로 채운 칸만), 없으면 null. */
public record FillSlotResponse(int index, String imageUrl, boolean filledByMe, String filledByName, Instant filledAt,
                               String clipUrl) {
}
