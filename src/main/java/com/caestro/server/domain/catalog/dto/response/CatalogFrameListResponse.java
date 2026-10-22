package com.caestro.server.domain.catalog.dto.response;

import java.time.Instant;
import java.util.List;

/** {@code updatedAt} 은 목록 중 가장 최근 수정 시각(비어 있으면 null) — 클라이언트 캐시와 ETag 의 근거. */
public record CatalogFrameListResponse(Instant updatedAt, List<CatalogFrameResponse> frames) {
}
