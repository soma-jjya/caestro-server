package com.caestro.server.domain.catalog.dto.response;

import com.caestro.server.domain.catalog.dto.LocalizedText;
import java.time.Instant;

public record CatalogBannerResponse(String id, LocalizedText title, LocalizedText body, LocalizedText chip, String imageUrl,
                                    int width, int height, String action, String linkUrl, int sortOrder, boolean hidden,
                                    Instant createdAt, Instant updatedAt) {
}
