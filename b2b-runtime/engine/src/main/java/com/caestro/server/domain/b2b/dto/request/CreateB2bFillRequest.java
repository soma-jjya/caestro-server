package com.caestro.server.domain.b2b.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 선택 화면에서 받은 버전도 보내야 변경된 프레임으로 조용히 생성되지 않는다. */
public record CreateB2bFillRequest(
        @NotBlank @Size(max = 40) String frameId,
        @NotNull @Min(1) Integer frameVersion,
        @Size(max = 30) String title
) {}
