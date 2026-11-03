package com.caestro.server.domain.ops.dto.response;

import java.util.List;

/** 최신순 한 페이지. {@code nextCursor} 가 null 이면 끝. */
public record OpsSubscriptionPageResponse(List<OpsSubscriptionResponse> items, Long nextCursor) {
}
