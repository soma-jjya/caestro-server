package com.caestro.server.domain.b2b.dto.response;

import com.caestro.server.domain.b2b.entity.B2bFrame;
import com.caestro.server.domain.b2b.entity.B2bImage;
import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import java.util.List;

public record B2bFrameResponse(
        String id, String name, B2bFrame.Layout layout,
        String imageId, String imageUrl, Integer width, Integer height,
        List<FrameWindowDto> windows, int displayOrder, int version
) {
    public static B2bFrameResponse from(B2bFrame frame, List<FrameWindowDto> windows, String imageUrl) {
        B2bImage image = frame.getImage();
        return new B2bFrameResponse(frame.getId(), frame.getName(), frame.getLayout(),
                image == null ? null : image.getSourceImageId(), imageUrl,
                image == null ? null : image.getWidth(), image == null ? null : image.getHeight(),
                windows, frame.getDisplayOrder(), frame.getVersion());
    }
}
