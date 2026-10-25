package com.caestro.server.domain.b2b.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record B2bSlugCheckResponse(boolean available, String reason) {}
