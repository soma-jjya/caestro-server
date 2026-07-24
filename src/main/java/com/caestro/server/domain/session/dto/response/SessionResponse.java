package com.caestro.server.domain.session.dto.response;

import com.caestro.server.domain.session.entity.Session;
import java.time.LocalDateTime;

public record SessionResponse(
        Long id,
        String sessionCode,
        String status,
        String cameraMode,
        LocalDateTime connectedAt,
        LocalDateTime endedAt,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {

    /**
     * Session 엔티티를 클라이언트 응답용 DTO로 변환한다.
     *
     * @param session 변환할 세션 엔티티
     * @return 세션 응답 DTO
     */
    public static SessionResponse from(Session session) {
        return new SessionResponse(
                session.getId(),
                session.getSessionCode(),
                session.getStatus(),
                session.getCameraMode(),
                session.getConnectedAt(),
                session.getEndedAt(),
                session.getExpiresAt(),
                session.getCreatedAt()
        );
    }
}
