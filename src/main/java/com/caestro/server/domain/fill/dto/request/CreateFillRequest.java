package com.caestro.server.domain.fill.dto.request;

import com.caestro.server.domain.fill.dto.FillCustomFrameDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFillRequest(
        @NotBlank(message = "frame은 필수입니다") @Size(max = 20) String frame,
        @NotBlank(message = "layout은 필수입니다") @Size(max = 20) String layout,
        @Size(max = 40) String artFrameId,
        FillCustomFrameDto customFrame
) {
}
