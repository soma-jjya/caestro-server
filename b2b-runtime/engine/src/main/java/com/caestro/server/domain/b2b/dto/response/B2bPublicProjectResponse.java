package com.caestro.server.domain.b2b.dto.response;

import com.caestro.server.domain.b2b.entity.B2bProject;
import java.util.List;

public record B2bPublicProjectResponse(String name, String slug, String description, List<B2bFrameResponse> frames) {
    public static B2bPublicProjectResponse from(B2bProject project, List<B2bFrameResponse> frames) {
        return new B2bPublicProjectResponse(project.getName(), project.getSlug(), project.getDescription(), frames);
    }
}
