package com.caestro.server.domain.deveval.dto.response;

/**
 * 관리자 요약: 기록 수, 올린 사람 수, 사진 있는 수, 가이드 있음·없음의 수와 평균 만족도, 가장 많이 쓴 만점.
 * avgRating 은 기록마다 rating/ratingMax 로 바꿔 평균한 뒤 ratingMax 를 곱한 값(소수 둘째 자리) — 모두 같은 만점이면 그냥 평균이다.
 * 기록이 없으면 avgRating·ratingMax 는 null.
 */
public record DevEvaluationStatsResponse(long count, long users, long withPhoto, Group guide, Group noGuide, Integer ratingMax) {

    public record Group(long count, Double avgRating) {
    }
}
