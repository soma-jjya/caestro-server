package com.caestro.server.domain.fill.dto.request;

import com.caestro.server.domain.fill.dto.FillCustomFrameDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 주인이 세션의 프레임을 통째로 바꾼다(PUT 의미). artFrameId·customFrame 을 비우면 그 프레임을 떼는 것이고,
 * 커스텀 프레임 요소 PNG 는 이 요청 뒤 frame-assets 로 다시 올린다(이전 요소는 서버가 지운다).
 */
public record UpdateFrameRequest(
        @NotBlank(message = "frame은 필수입니다") @Size(max = 20) String frame,
        @NotBlank(message = "layout은 필수입니다") @Size(max = 20) String layout,
        @Size(max = 40) String artFrameId,
        FillCustomFrameDto customFrame,
        Boolean borderless
) {
}
