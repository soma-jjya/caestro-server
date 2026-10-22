package com.caestro.server.domain.catalog.dto.response;

import java.time.Instant;
import java.util.List;

/** 포즈추천 덧씌우기 + 스튜디오 업로드. {@code updatedAt} 은 둘을 통틀어 가장 최근 수정 시각. */
public record CatalogPoseResponse(Instant updatedAt, List<CatalogPoseOverrideResponse> overrides, List<CatalogPoseUploadResponse> uploads) {
}
