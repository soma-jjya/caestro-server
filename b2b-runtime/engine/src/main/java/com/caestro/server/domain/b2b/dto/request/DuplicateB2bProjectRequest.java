package com.caestro.server.domain.b2b.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DuplicateB2bProjectRequest(
        @NotBlank @Size(max = 60) String name,
        @NotBlank @Pattern(regexp = "^[a-z0-9-]{3,40}$") String slug
) {}
