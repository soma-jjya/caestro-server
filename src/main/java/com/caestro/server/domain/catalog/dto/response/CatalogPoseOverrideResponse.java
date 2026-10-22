package com.caestro.server.domain.catalog.dto.response;

import java.time.Instant;
import java.util.Map;

/** 휴지통에 있으면 hidden=true 로 내려가고 deletedAt·purgeAt 이 붙는다. */
public record CatalogPoseOverrideResponse(String version, String shot, boolean hidden, Map<String, String> labels,
                                          Instant updatedAt, Instant deletedAt, Instant purgeAt) {
}
