package com.caestro.server.domain.signaling.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class SessionInfo {
    private String sessionCode;
    private Long directorUserId;
    private String directorSocketId;
    private Long cameraUserId;
    private String cameraSocketId;
    private String status; // WAITING, CONNECTED, ENDED
    private LocalDateTime expiresAt;
    private String liteToken; // 라이트 모드(비로그인 촬영자) 참여 토큰
}
