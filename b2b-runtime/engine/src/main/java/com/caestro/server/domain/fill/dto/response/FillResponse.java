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
        String title,
        boolean borderless,
        List<FillSlotResponse> slots,
        /** 완성된 네컷의 움직이는 GIF 주소; 올라온 적 없거나 그 뒤 칸·프레임이 바뀌었으면 null. */
        String gifUrl,
        Instant expiresAt,
        Instant createdAt,
        /** 칸에서 밀려난 사진들, 먼저 밀려난 것부터(맨 아래가 가장 최근). 되돌리거나 지울 수 있다. */
        List<FillQueueItemResponse> queue
) {
}
