package com.caestro.server.domain.deveval.controller;

import com.caestro.server.domain.deveval.controller.api.DevEvaluationApi;
import com.caestro.server.domain.deveval.dto.request.UpsertDevEvaluationRequest;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationSavedResponse;
import com.caestro.server.domain.deveval.service.DevEvaluationService;
import com.caestro.server.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 앱 개발자 모드의 평가 올리기 (#260, contracts/dev-evaluations.md). 앱 토큰(게스트 포함)이면 누구나, 자기 기록만. */
@RestController
@RequestMapping("/dev/evaluations")
@RequiredArgsConstructor
public class DevEvaluationController implements DevEvaluationApi {

    private final DevEvaluationService evaluationService;

    @PutMapping(value = "/{clientId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Override
    public ResponseEntity<DevEvaluationSavedResponse> upsert(@PathVariable String clientId,
                                                             @RequestBody UpsertDevEvaluationRequest request,
                                                             @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(evaluationService.upsert(userDetails.getUserId(), clientId, request));
    }

    // 형식이 빠진 요청(octet-stream)도 받아 서비스가 앞 바이트로 확인한다
    @PutMapping(value = "/{clientId}/photo",
            consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp", MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Override
    public ResponseEntity<Void> uploadPhoto(@PathVariable String clientId,
                                            @RequestBody(required = false) byte[] body,
                                            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                            @AuthenticationPrincipal CustomUserDetails userDetails) {
        evaluationService.uploadPhoto(userDetails.getUserId(), clientId, body, contentType);
        return ResponseEntity.noContent().build();
    }
}
