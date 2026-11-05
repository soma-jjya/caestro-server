package com.caestro.server.domain.b2b.dto.response;

import com.caestro.server.domain.fill.dto.response.FillResponse;

/** frame은 참여 시점의 불변 설정, fill은 기존 네컷 상태다. 사진 합성·다운로드는 FE에서 한다. */
public record B2bFillResponse(String slug, String shareUrl, B2bFrameResponse frame, FillResponse fill) {}
