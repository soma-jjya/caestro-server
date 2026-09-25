package com.caestro.server.domain.fill.dto;

import java.util.List;

/** 사용자 제작 프레임: 바탕색과 요소 배치. 요소 PNG의 {@code url}은 업로드 뒤 서버가 채운다. */
public record FillCustomFrameDto(String base, List<FillLayerDto> layers) {
}
