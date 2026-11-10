package com.caestro.server.domain.deveval.dto.response;

import com.caestro.server.domain.deveval.dto.PhotoScoreDto;
import java.time.Instant;
import java.util.Map;

/**
 * 관리자 목록 한 줄: 앱이 보낸 기록 그대로 + 누가(사용자 id·닉네임·기기 키 앞 16자) + 사진 유무·크기 + 서버가 받은 시각.
 * 사진 바이트는 싣지 않는다(GET /admin/dev/evaluations/{id}/photo).
 */
public record DevEvaluationRowResponse(
        String id,
        Long userId,
        String userNickname,
        String deviceKey,
        int rating,
        int ratingMax,
        boolean guide,
        String guideVersion,
        String guideShot,
        String guideStyle,
        Double shutterScore,
        PhotoScoreDto photoScore,
        String mediaType,
        Instant takenAt,
        Instant ratedAt,
        Integer width,
        Integer height,
        String appVersion,
        String device,
        String note,
        Map<String, Object> extra,
        boolean hasPhoto,
        Integer photoBytes,
        Instant createdAt,
        Instant updatedAt
) {
}
