package com.caestro.server.domain.fill.dto.request;

import com.caestro.server.domain.fill.dto.FillCustomFrameDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFillRequest(
        @NotBlank(message = "frame은 필수입니다") @Size(max = 20) String frame,
        @NotBlank(message = "layout은 필수입니다") @Size(max = 20) String layout,
        @Size(max = 40) String artFrameId,
        FillCustomFrameDto customFrame,
        /** 주인이 붙인 제목(선택). 공백만이면 없는 것으로 본다. */
        @Size(max = 30) String title,
        /** 무테(선택). 비우면 커스텀 프레임의 무테 여부를 따르고, 그것도 없으면 false. */
        Boolean borderless
) {
}
