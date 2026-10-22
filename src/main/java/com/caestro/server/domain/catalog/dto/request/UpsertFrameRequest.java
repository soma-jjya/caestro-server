package com.caestro.server.domain.catalog.dto.request;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.dto.LocalizedText;
import java.util.List;

/** 프레임 생성·수정 본문. 수정(PUT)에서는 보낸 필드만 바뀐다(null 은 그대로). */
public record UpsertFrameRequest(
        LocalizedText title,
        LocalizedText subtitle,
        String imageId,
        List<FrameWindowDto> windows,
        Integer sortOrder,
        Boolean hidden
) {
}
