package com.caestro.server.domain.signaling.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class SessionInfo {
    private String sessionCode;
    private Long ownerUserId;
    private String ownerSocketId;
    private Long participantUserId;
    private String participantSocketId;
    private Long currentDirectorUserId;
    private String status; // WAITING, CONNECTED, ENDED
    private LocalDateTime expiresAt;
}
