package com.caestro.server.domain.catalog.dto.response;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.dto.LocalizedText;
import java.time.Instant;
import java.util.List;

/**
 * 기본 프레임 하나의 덮어쓴 값. null 인 필드는 앱에 든 값을 쓴다.
 *
 * @param kind     base(검정·흰색) | art(AI 프레임)
 * @param hidden   앱 목록에서 뺀다(숨김이거나 휴지통에 있으면 true)
 * @param deletedAt 휴지통에 넣은 시각(살아 있으면 null)
 * @param imageUrl 바꾼 그림(없으면 앱에 든 그림), width·height·windows 도 그때만
 */
public record CatalogBuiltinFrameResponse(String id, String kind, LocalizedText title, LocalizedText subtitle, Boolean pro,
                                          LocalizedText badge, boolean hidden, Instant deletedAt, String imageUrl,
                                          Integer width, Integer height, List<FrameWindowDto> windows, Instant updatedAt) {
}
