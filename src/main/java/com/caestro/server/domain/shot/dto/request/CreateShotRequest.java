package com.caestro.server.domain.shot.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 촬영 결과물 메타데이터 저장 요청.
 * director_id / camera_id / image_url / is_cloud_backed 는 서버에서 결정하므로 요청 바디로 받지 않으며,
 * 클라이언트가 임의로 보내더라도 무시된다 (ignoreUnknown).
 * 단일 필드 검증(양수/범위)은 이 DTO에서 Bean Validation으로 처리하고,
 * 교차 필드 검증(둘 다/둘 다 없음)·mode 유효성·세션 존재 여부는 서비스에서 처리한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateShotRequest(

        // 협업(COLLAB)에서 결과물이 속한 세션 코드. 클라이언트가 WS에서 받은 값을 그대로 사용한다.
        String sessionCode,

        // 협업(COLLAB)에서 이 컷의 디렉터(피사체) userId. 역할은 컷마다 다를 수 있어 클라이언트가 명시한다.
        // 없으면 요청자를 디렉터로 폴백하며, 서버는 두 참여자가 세션 참여자인지 검증한다.
        Long directorUserId,

        String mode,

        Integer bestCutScore,

        @Positive(message = "width는 양의 정수여야 합니다")
        Integer width,

        @Positive(message = "height는 양의 정수여야 합니다")
        Integer height,

        @Positive(message = "fileSizeKb는 양의 정수여야 합니다")
        Integer fileSizeKb,

        @DecimalMin(value = "-90", message = "latitude는 -90 이상이어야 합니다")
        @DecimalMax(value = "90", message = "latitude는 90 이하여야 합니다")
        BigDecimal latitude,

        @DecimalMin(value = "-180", message = "longitude는 -180 이상이어야 합니다")
        @DecimalMax(value = "180", message = "longitude는 180 이하여야 합니다")
        BigDecimal longitude,

        LocalDateTime takenAt
) {
}
