package com.caestro.server.domain.b2b.dto.request;

import com.caestro.server.domain.b2b.entity.B2bFrame.Layout;
import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReplaceB2bFramesRequest(
        @NotNull @Size(max = 3) List<@NotNull @Valid Frame> frames
) {
    public record Frame(
            @Size(max = 40) String id,
            @NotBlank @Size(max = 30) String name,
            @NotNull Layout layout,
            @Size(max = 32) String imageId,
            @Size(max = 4) List<@NotNull FrameWindowDto> windows
    ) {}
}
