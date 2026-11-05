package com.caestro.server.domain.b2b.dto.response;

import com.caestro.server.domain.b2b.entity.B2bProject;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

public record B2bProjectResponse(
        String id, String name, String slug, String description, B2bProject.Status status,
        List<B2bFrameResponse> frames, Instant publishedAt, Instant createdAt, Instant updatedAt
) {
    public static B2bProjectResponse from(B2bProject project, List<B2bFrameResponse> frames, ZoneId zone) {
        return new B2bProjectResponse(project.getId(), project.getName(), project.getSlug(), project.getDescription(),
                project.getStatus(), frames,
                project.getPublishedAt() == null ? null : project.getPublishedAt().atZone(zone).toInstant(),
                project.getCreatedAt().atZone(zone).toInstant(), project.getUpdatedAt().atZone(zone).toInstant());
    }
}
