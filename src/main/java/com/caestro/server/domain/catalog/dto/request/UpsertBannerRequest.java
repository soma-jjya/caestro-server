package com.caestro.server.domain.catalog.dto.request;

import com.caestro.server.domain.catalog.dto.LocalizedText;

/**
 * 배너 생성·수정 본문. 수정(PUT)에서는 보낸 필드만 바뀐다(null 은 그대로).
 * {@code videoId} 는 /catalog/studio/videos 로 올린 동영상, {@code clearVideo}=true 면 동영상을 뗀다.
 */
public record UpsertBannerRequest(
        LocalizedText title,
        LocalizedText body,
        LocalizedText chip,
        String imageId,
        String action,
        String linkUrl,
        Integer sortOrder,
        Boolean hidden,
        String videoId,
        Boolean clearVideo
) {
}
