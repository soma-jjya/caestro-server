package com.caestro.server.domain.deveval.dto.response;

import java.util.List;

/** 최근 고친 것부터 한 페이지. page 는 0부터, total 은 조건에 맞는 전체 수. */
public record DevEvaluationPageResponse(List<DevEvaluationRowResponse> items, int page, int size, long total) {
}
