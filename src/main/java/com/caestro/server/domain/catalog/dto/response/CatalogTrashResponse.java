package com.caestro.server.domain.catalog.dto.response;

import java.util.List;

/** 휴지통: 지운 지 7일 안의 항목. 각 항목에 deletedAt·purgeAt 이 붙는다. 기본 프레임(builtins)은 지워지지 않고 되살릴 때까지 남는다. */
public record CatalogTrashResponse(List<CatalogFrameResponse> frames, List<CatalogBannerResponse> banners,
                                   List<CatalogPoseUploadResponse> poseUploads, List<CatalogPoseOverrideResponse> poseOverrides,
                                   List<CatalogBuiltinFrameResponse> builtins) {

    /** 5차 전 모양(기본 프레임 없이) */
    public CatalogTrashResponse(List<CatalogFrameResponse> frames, List<CatalogBannerResponse> banners,
                                List<CatalogPoseUploadResponse> poseUploads, List<CatalogPoseOverrideResponse> poseOverrides) {
        this(frames, banners, poseUploads, poseOverrides, List.of());
    }
}
