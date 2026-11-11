package com.caestro.server.domain.deveval.controller.api;

import com.caestro.server.domain.deveval.dto.request.UpsertDevEvaluationRequest;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationSavedResponse;
import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Dev Evaluations", description = "앱 개발자 모드의 사진 평가(만족도·가이드 유무)와 사진 올리기. 앱 토큰(게스트 포함), 자기 기록만 (contracts/dev-evaluations.md)")
@SecurityRequirement(name = "BearerAuth")
public interface DevEvaluationApi {

    @Operation(summary = "평가 기록 올리기(만들기·덮어쓰기)",
            description = "clientId 는 앱이 사진마다 만든 UUID. 같은 clientId 로 다시 보내면 통째로 덮어씁니다. "
                    + "필수: rating(1..ratingMax)·ratingMax(2..100)·guide·mediaType(photo|fourcut)·ratedAt(ISO-8601). 본문 최대 32KB, extra 는 4KB.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장됨. hasPhoto 가 false 면 사진을 이어서 올린다",
                    content = @Content(schema = @Schema(implementation = DevEvaluationSavedResponse.class))),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음(DEV_EVAL_INVALID, 무엇이 틀렸는지 message 에)"),
            @ApiResponse(responseCode = "403", description = "토큰 없음, 또는 다른 사용자의 clientId(DEV_EVAL_FORBIDDEN)"),
            @ApiResponse(responseCode = "409", description = "사용자당 3000개 초과(DEV_EVAL_LIMIT)"),
            @ApiResponse(responseCode = "411", description = "Content-Length 없음"),
            @ApiResponse(responseCode = "413", description = "본문 32KB 초과(DEV_EVAL_TOO_LARGE)")
    })
    ResponseEntity<DevEvaluationSavedResponse> upsert(@Parameter(description = "앱이 만든 UUID") String clientId,
                                                      UpsertDevEvaluationRequest request,
                                                      @Parameter(hidden = true) CustomUserDetails userDetails);

    @Operation(summary = "평가 사진 올리기",
            description = "본문은 사진 바이트(image/jpeg·image/png·image/webp, 최대 8MB). 기록을 먼저 올려야 합니다. 이전 사진은 바꿉니다. "
                    + "사진은 공개 주소 없이 서버 DB 에만 두고 관리자만 봅니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "저장됨"),
            @ApiResponse(responseCode = "400", description = "JPEG/PNG/WebP 가 아님(DEV_EVAL_INVALID_PHOTO)"),
            @ApiResponse(responseCode = "403", description = "토큰 없음, 또는 다른 사용자의 기록(DEV_EVAL_FORBIDDEN)"),
            @ApiResponse(responseCode = "404", description = "기록 없음(DEV_EVAL_NOT_FOUND)"),
            @ApiResponse(responseCode = "411", description = "Content-Length 없음"),
            @ApiResponse(responseCode = "413", description = "8MB 초과(DEV_EVAL_PHOTO_TOO_LARGE)")
    })
    ResponseEntity<Void> uploadPhoto(String clientId, byte[] body, String contentType,
                                     @Parameter(hidden = true) CustomUserDetails userDetails);

    @Operation(summary = "직접 올린 사진 가이드 이미지 올리기",
            description = "평가한 사진을 '직접 올린 사진' 가이드(guideVersion=user)로 찍었을 때, 그 가이드의 이미지를 올립니다. "
                    + "kind 는 photo(실사) 또는 line(라인). 사진과 같은 규칙(기록 먼저, JPEG·PNG·WebP, 최대 8MB)이고 같은 kind 는 바꿉니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "저장됨"),
            @ApiResponse(responseCode = "400", description = "kind 가 photo·line 이 아님(DEV_EVAL_INVALID), 이미지가 아님(DEV_EVAL_INVALID_PHOTO)"),
            @ApiResponse(responseCode = "403", description = "토큰 없음, 또는 다른 사용자의 기록(DEV_EVAL_FORBIDDEN)"),
            @ApiResponse(responseCode = "404", description = "기록 없음(DEV_EVAL_NOT_FOUND)"),
            @ApiResponse(responseCode = "411", description = "Content-Length 없음"),
            @ApiResponse(responseCode = "413", description = "8MB 초과(DEV_EVAL_PHOTO_TOO_LARGE)")
    })
    ResponseEntity<Void> uploadGuide(@Parameter(description = "앱이 만든 UUID") String clientId,
                                     @Parameter(description = "photo | line") String kind,
                                     byte[] body, String contentType,
                                     @Parameter(hidden = true) CustomUserDetails userDetails);
}
