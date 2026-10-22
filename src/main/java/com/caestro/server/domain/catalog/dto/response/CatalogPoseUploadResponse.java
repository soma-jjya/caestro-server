package com.caestro.server.domain.catalog.dto.response;

import java.time.Instant;
import java.util.Map;

public record CatalogPoseUploadResponse(String id, String imageUrl, int width, int height, Map<String, String> labels,
                                        int sortOrder, boolean hidden, Instant createdAt, Instant updatedAt,
                                        Instant deletedAt, Instant purgeAt) {
}
