package com.caestro.server.domain.catalog.dto.response;

public record CatalogImageResponse(String imageId, String url, int width, int height, String contentType) {
}
