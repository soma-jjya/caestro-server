package com.caestro.server.domain.signaling.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SignalingResponse(
        String type,
        String sessionCode,
        String sender,
        String sdpType,
        String sdp,
        String sdpMid,
        Integer sdpMLineIndex,
        String candidate,
        String message, // 에러나 시스템 메시지용
        Long currentDirectorUserId, // ROLE_SWAPPED 응답: 교체 후 현재 디렉터인 유저
        String status // SESSION_RESUMED 응답: 복원 시점의 세션 상태(WAITING|CONNECTED) — 공백 중 상대 이탈 여부 복구용
) {
}
