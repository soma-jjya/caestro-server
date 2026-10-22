package com.caestro.server.domain.catalog.dto.response;

/** 올린 배너 동영상. url 은 그림과 같은 /catalog/images/{id} (Range 요청을 받는다). */
public record CatalogVideoResponse(String videoId, String url, int width, int height, int durationMs, String contentType) {
}
