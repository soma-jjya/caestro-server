package com.caestro.server.domain.deveval.dto.response;

import java.time.Instant;

/** 목록 행의 자동 사진 점수(#289). 앱이 보낸 photoScore 와 따로 둔다. 매긴 적 없거나 지워졌으면 행의 값이 null. */
public record AutoScoreResponse(String status, Double total, Double pose, Double place, Double shape, Integer algo, Instant scoredAt) {
}
