package com.caestro.server.domain.deveval.dto.request;

import com.caestro.server.domain.deveval.dto.PhotoScoreDto;
import java.util.Map;

/**
 * 앱이 보내는 평가 기록 (PUT /dev/evaluations/{clientId}, contracts/dev-evaluations.md). 통째로 바꾼다.
 * 필수는 rating·ratingMax·guide·mediaType·ratedAt, 나머지는 선택. 시각은 ISO-8601 문자열(서비스가 읽어 400 문구를 정한다).
 */
public record UpsertDevEvaluationRequest(
        Integer rating,
        Integer ratingMax,
        Boolean guide,
        String guideVersion,
        String guideShot,
        String guideStyle,
        Double shutterScore,
        PhotoScoreDto photoScore,
        String mediaType,
        String takenAt,
        String ratedAt,
        Integer width,
        Integer height,
        String appVersion,
        String device,
        String note,
        Map<String, Object> extra
) {
}
