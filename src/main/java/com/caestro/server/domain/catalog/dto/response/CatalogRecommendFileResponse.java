package com.caestro.server.domain.catalog.dto.response;

/** 헤드·모델 파일 올리기 결과. kind 는 head | model, 버전을 만들 때 id 를 headId·modelId 로 묶는다. */
public record CatalogRecommendFileResponse(String id, String url, long bytes, String kind) {
}
