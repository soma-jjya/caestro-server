package com.caestro.server.domain.shot.dto.response;

import com.caestro.server.domain.shot.entity.Shot;
import com.caestro.server.domain.shot.enums.ShotMode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ShotResponse(
        Long id,
        Long sessionId,
        Long directorId,
        Long cameraId,
        ShotMode mode,
        Integer bestCutScore,
        String imageUrl,
        Integer width,
        Integer height,
        Integer fileSizeKb,
        BigDecimal latitude,
        BigDecimal longitude,
        Boolean isCloudBacked,
        LocalDateTime takenAt
) {

    /**
     * Shot 엔티티를 클라이언트 응답용 DTO로 변환한다.
     * 연관 엔티티(session/director/camera)는 ID만 추출해 노출한다.
     *
     * @param shot 변환할 촬영 결과물 엔티티
     * @return 촬영 결과물 응답 DTO
     */
    public static ShotResponse from(Shot shot) {
        return new ShotResponse(
                shot.getId(),
                shot.getSession() != null ? shot.getSession().getId() : null,
                shot.getDirector().getId(),
                shot.getCamera() != null ? shot.getCamera().getId() : null,
                shot.getMode(),
                shot.getBestCutScore(),
                shot.getImageUrl(),
                shot.getWidth(),
                shot.getHeight(),
                shot.getFileSizeKb(),
                shot.getLatitude(),
                shot.getLongitude(),
                shot.getIsCloudBacked(),
                shot.getTakenAt()
        );
    }
}
