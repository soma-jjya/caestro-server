package com.caestro.server.domain.catalog.dto.response;

import com.caestro.server.domain.catalog.dto.LocalizedText;
import java.time.Instant;

/**
 * 휴지통에 있을 때만 deletedAt·purgeAt 이 값을 가진다(살아 있는 행은 null).
 * videoUrl·videoDurationMs 는 동영상 배너일 때만(아니면 null).
 */
public record CatalogBannerResponse(String id, LocalizedText title, LocalizedText body, LocalizedText chip, String imageUrl,
                                    int width, int height, String action, String linkUrl, int sortOrder, boolean hidden,
                                    Instant createdAt, Instant updatedAt, Instant deletedAt, Instant purgeAt,
                                    String videoUrl, Integer videoDurationMs) {
}
