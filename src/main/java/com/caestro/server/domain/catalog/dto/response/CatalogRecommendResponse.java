package com.caestro.server.domain.catalog.dto.response;

import java.time.Instant;
import java.util.Map;

/** 스튜디오의 추천 설정 버전 한 개. head·model 이 null 이면 앱 번들 것, publishedAt 은 마지막으로 게시한 시각(없으면 null). */
public record CatalogRecommendResponse(String id, String name, String note, Map<String, Object> params,
                                       RecommendFileDto head, RecommendFileDto model, boolean active,
                                       Instant createdAt, Instant updatedAt, Instant publishedAt) {
}
