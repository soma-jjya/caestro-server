package com.caestro.server.domain.fill.dto;

import java.util.List;

/**
 * 사용자 제작 프레임: 바탕색과 요소 배치, 무테 여부. 요소 PNG의 {@code url}은 업로드 뒤 서버가 채운다.
 * 레이아웃은 세션의 layout 을 따른다(커스텀 프레임은 만든 레이아웃으로만 쓰인다).
 */
public record FillCustomFrameDto(String base, List<FillLayerDto> layers, Boolean borderless) {
}
