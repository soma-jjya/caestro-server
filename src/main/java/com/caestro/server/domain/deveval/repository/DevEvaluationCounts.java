package com.caestro.server.domain.deveval.repository;

/**
 * 관리자 요약 숫자를 한 번에 센 결과. 평균은 기록마다 rating/ratingMax(0..1)로 바꿔 낸 값이라 만점이 섞여도 비교할 수 있다.
 * 기록이 없으면 평균은 null.
 */
public record DevEvaluationCounts(long count, long users, long withPhoto,
                                  long guideCount, Double guideRatio, long noGuideCount, Double noGuideRatio) {
}
