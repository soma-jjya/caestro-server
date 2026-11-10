package com.caestro.server.domain.deveval.dto.response;

/** 기록 저장 결과. hasPhoto 가 false 면 앱이 이어서 사진을 올린다. */
public record DevEvaluationSavedResponse(String id, boolean hasPhoto) {
}
