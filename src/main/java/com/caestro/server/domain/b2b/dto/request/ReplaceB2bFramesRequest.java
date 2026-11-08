package com.caestro.server.domain.b2b.dto.request;

import com.caestro.server.domain.b2b.entity.B2bFrame.Layout;
import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReplaceB2bFramesRequest(
        @NotNull @Size(max = ReplaceB2bFramesRequest.MAX_FRAMES) List<@NotNull @Valid Frame> frames
) {
    /** 프로젝트당 프레임 상한. 축제 전용 서버(peakpic-b2b-server)도 같은 값이어야 반영이 된다 (#237) */
    public static final int MAX_FRAMES = 10;

    public record Frame(
            @Size(max = 40) String id,
            @NotBlank @Size(max = 30) String name,
            @NotNull Layout layout,
            @Size(max = 32) String imageId,
            @Size(max = 4) List<@NotNull FrameWindowDto> windows
    ) {}
}
