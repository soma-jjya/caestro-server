package com.caestro.server.domain.b2b.service;

import com.caestro.server.domain.b2b.dto.response.B2bFrameResponse;
import com.caestro.server.domain.b2b.entity.B2bFrame;
import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class B2bFrameMapper {
    private static final TypeReference<List<FrameWindowDto>> WINDOWS = new TypeReference<>() {};
    private final ObjectMapper objectMapper;
    @Value("${catalog.public-base-url:https://api.peakpic.app}")
    private String apiBaseUrl;

    /** 현재 프레임을 응답 및 세션 스냅샷으로 변환한다. */
    public B2bFrameResponse response(B2bFrame frame) {
        return B2bFrameResponse.from(frame, windows(frame.getWindowsJson()), frame.getImage() == null ? null
                : apiBaseUrl.replaceAll("/+$", "") + "/b2b/images/" + frame.getImage().getId());
    }

    /** 서버가 검증한 설정을 JSON으로 고정한다. 직렬화 실패는 저장하지 않고 500으로 처리한다. */
    public String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /** DB에 저장된 창을 읽는다. 손상된 설정을 기본값으로 대체하지 않는다. */
    public List<FrameWindowDto> windows(String json) {
        try {
            return objectMapper.readValue(json, WINDOWS);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /** 운영자의 현재 프레임이 아닌, 참여 당시의 스냅샷을 읽는다. */
    public B2bFrameResponse snapshot(String json) {
        try {
            return objectMapper.readValue(json, B2bFrameResponse.class);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
