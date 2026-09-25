package com.caestro.server.domain.fill.dto.response;

import com.caestro.server.domain.fill.dto.FillCustomFrameDto;
import java.time.Instant;
import java.util.List;

/** 세션의 전체 상태. 시각은 UTC Instant(…Z)로 내려 시간대 해석이 클라이언트마다 갈리지 않게 한다. */
public record FillResponse(
        String code,
        boolean ownerMe,
        String frame,
        String layout,
        String artFrameId,
        FillCustomFrameDto customFrame,
        List<FillSlotResponse> slots,
        Instant expiresAt,
        Instant createdAt
) {
}
