package com.caestro.server.domain.catalog.dto.response;

import java.time.Instant;
import java.util.Map;

/**
 * 앱이 받는 게시본 (GET /catalog/recommend). params 안의 키는 모두 선택(없으면 앱 기본값), head·model 이 null 이면 앱 번들 것.
 * updatedAt 은 게시한 시각이다 — 게시 중인 버전은 고칠 수 없으므로 내용이 마지막으로 바뀐 때와 같다. 게시본이 없으면 본문은 {@code {}}.
 */
public record CatalogRecommendManifest(String id, String name, Instant updatedAt, Map<String, Object> params,
                                       RecommendFileDto head, RecommendFileDto model) {
}
