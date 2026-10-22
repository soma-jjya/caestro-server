package com.caestro.server.domain.catalog.dto.request;

import java.util.Map;

/**
 * 파이프라인 샷 덧씌우기. 보낸 필드만 바뀐다: hidden, labels(통째로 교체), deleted(true 면 휴지통, false 면 휴지통에서 꺼냄).
 */
public record UpsertPoseOverrideRequest(Boolean hidden, Map<String, String> labels, Boolean deleted) {
}
