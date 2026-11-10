package com.caestro.server.domain.deveval.dto;

/** 갤러리 점수 매기기(PhotoScorer) 결과. total·pose 는 0..100, place·shape 는 0..1 이 보통이고 각 칸은 null 일 수 있다. */
public record PhotoScoreDto(Double total, Double pose, Double place, Double shape) {
}
