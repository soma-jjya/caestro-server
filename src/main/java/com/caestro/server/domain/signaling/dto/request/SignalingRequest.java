package com.caestro.server.domain.signaling.dto.request;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SignalingRequest(
        String type,
        String sessionCode,
        String sender,
        String sdpType,
        String sdp,
        String sdpMid,
        Integer sdpMLineIndex,
        String candidate,
        // DEVICE_SPEC 메시지용 기기 스펙 필드
        String role,
        BigDecimal maxZoom,
        BigDecimal minZoom,
        BigDecimal screenRatio,
        String maxResolution,
        String osType
) {
    public SignalingRequest {
        // 수동 검증: type이 없는 메시지는 처리할 수 없으므로 즉시 거부
        if (type == null || type.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_SIGNALING_MESSAGE);
        }
    }
}
