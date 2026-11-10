package com.caestro.server.domain.catalog.dto.response;

import java.util.List;

/** 스튜디오 목록: 게시 중인 버전 id(없으면 null)와 버전들(만든 순서 최신부터). */
public record CatalogRecommendListResponse(String activeId, List<CatalogRecommendResponse> items) {
}
