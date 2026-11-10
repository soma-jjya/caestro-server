package com.caestro.server.domain.deveval.entity;

import java.time.LocalDateTime;

/** 앱이 보낸 평가 기록을 검증·정리한 값. 기록을 만들거나 덮어쓸 때 통째로 쓴다(PUT 은 전체 교체). */
public record DevEvaluationValues(
        int rating,
        int ratingMax,
        boolean guide,
        String guideVersion,
        String guideShot,
        String guideStyle,
        Double shutterScore,
        Double scoreTotal,
        Double scorePose,
        Double scorePlace,
        Double scoreShape,
        String mediaType,
        LocalDateTime takenAt,
        LocalDateTime ratedAt,
        Integer width,
        Integer height,
        String appVersion,
        String device,
        String note,
        String extraJson
) {
}
