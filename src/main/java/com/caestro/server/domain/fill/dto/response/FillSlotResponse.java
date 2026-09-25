package com.caestro.server.domain.fill.dto.response;

import java.time.Instant;

public record FillSlotResponse(int index, String imageUrl, boolean filledByMe, String filledByName, Instant filledAt) {
}
