package com.caestro.server.domain.catalog.dto.response;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.dto.LocalizedText;
import java.time.Instant;
import java.util.List;

/** 휴지통에 있을 때만 deletedAt·purgeAt 이 값을 가진다(살아 있는 행은 null). */
public record CatalogFrameResponse(String id, LocalizedText title, LocalizedText subtitle, String imageUrl, int width, int height,
                                   List<FrameWindowDto> windows, int sortOrder, boolean hidden, Instant createdAt, Instant updatedAt,
                                   Instant deletedAt, Instant purgeAt) {
}
