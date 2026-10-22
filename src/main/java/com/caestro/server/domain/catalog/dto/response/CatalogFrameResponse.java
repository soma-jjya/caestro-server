package com.caestro.server.domain.catalog.dto.response;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.dto.LocalizedText;
import java.time.Instant;
import java.util.List;

public record CatalogFrameResponse(String id, LocalizedText title, LocalizedText subtitle, String imageUrl, int width, int height,
                                   List<FrameWindowDto> windows, int sortOrder, boolean hidden, Instant createdAt, Instant updatedAt) {
}
