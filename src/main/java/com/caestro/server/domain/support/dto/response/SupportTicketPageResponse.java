package com.caestro.server.domain.support.dto.response;

import java.util.List;

/** 한 페이지. {@code nextCursor} 가 null 이면 끝. */
public record SupportTicketPageResponse(List<SupportTicketSummaryResponse> items, Long nextCursor) {
}
