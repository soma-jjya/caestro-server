package com.caestro.server.domain.catalog.dto.request;

import java.util.Map;

/** 포즈 업로드 생성·수정 본문. 수정(PUT)에서는 보낸 필드만 바뀐다(labels 는 통째로 교체). */
public record UpsertPoseUploadRequest(String imageId, Map<String, String> labels, Integer sortOrder, Boolean hidden) {
}
