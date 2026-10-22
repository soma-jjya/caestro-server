package com.caestro.server.domain.catalog.dto.request;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.dto.LocalizedText;
import java.util.List;

/**
 * 프레임 생성·수정 본문. 수정(PUT)에서는 보낸 필드만 바뀐다(null 은 그대로).
 *
 * @param pro   Pro 전용인가. 생성에서 생략하면 true(Pro)
 * @param badge 제목 왼쪽 배지. 한국어가 비어 있으면 배지를 지운다
 */
public record UpsertFrameRequest(
        LocalizedText title,
        LocalizedText subtitle,
        String imageId,
        List<FrameWindowDto> windows,
        Integer sortOrder,
        Boolean hidden,
        Boolean pro,
        LocalizedText badge
) {

    /** 4차 전 모양(Pro·배지 없이): 새 프레임은 Pro, 배지 없음. */
    public UpsertFrameRequest(LocalizedText title, LocalizedText subtitle, String imageId, List<FrameWindowDto> windows,
                              Integer sortOrder, Boolean hidden) {
        this(title, subtitle, imageId, windows, sortOrder, hidden, null, null);
    }
}
