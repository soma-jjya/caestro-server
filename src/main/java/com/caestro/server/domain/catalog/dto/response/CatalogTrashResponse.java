package com.caestro.server.domain.catalog.dto.response;

import java.util.List;

/** 휴지통: 지운 지 7일 안의 항목. 각 항목에 deletedAt·purgeAt 이 붙는다. */
public record CatalogTrashResponse(List<CatalogFrameResponse> frames, List<CatalogBannerResponse> banners,
                                   List<CatalogPoseUploadResponse> poseUploads, List<CatalogPoseOverrideResponse> poseOverrides) {
}
