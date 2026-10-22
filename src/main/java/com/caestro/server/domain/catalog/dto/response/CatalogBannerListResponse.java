package com.caestro.server.domain.catalog.dto.response;

import java.time.Instant;
import java.util.List;

public record CatalogBannerListResponse(Instant updatedAt, List<CatalogBannerResponse> banners) {
}
