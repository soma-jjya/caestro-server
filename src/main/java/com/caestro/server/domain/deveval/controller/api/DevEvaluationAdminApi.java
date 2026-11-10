package com.caestro.server.domain.deveval.controller.api;

import com.caestro.server.domain.deveval.dto.response.DevEvaluationPageResponse;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationStatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Dev Evaluations Admin", description = "개발자 평가 열람·내려받기·지우기. X-Studio-Key(ADMIN JWT 도 가능), 앱 토큰은 403 (contracts/dev-evaluations.md)")
@SecurityRequirement(name = "StudioKey")
@ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치 또는 권한 없음")
public interface DevEvaluationAdminApi {

    @Operation(summary = "평가 목록", description = "최근 고친 것부터. 기록 + 사용자 id·닉네임·기기 키 앞 16자 + 사진 유무·크기.")
    @ApiResponse(responseCode = "200", description = "한 페이지",
            content = @Content(schema = @Schema(implementation = DevEvaluationPageResponse.class)))
    ResponseEntity<DevEvaluationPageResponse> list(
            @Parameter(description = "0부터. 기본 0") Integer page,
            @Parameter(description = "기본 50, 최대 200") Integer size,
            @Parameter(description = "true 가이드 있음 / false 없음 / 생략 전부") Boolean guide,
            @Parameter(description = "이 사용자의 것만") Long userId);

    @Operation(summary = "요약 숫자",
            description = "기록 수·올린 사람 수·사진 있는 수, 가이드 있음·없음의 수와 평균 만족도, 가장 많이 쓴 만점(ratingMax). "
                    + "평균은 기록마다 rating/ratingMax 로 맞춘 뒤 ratingMax 를 곱한 값입니다.")
    @ApiResponse(responseCode = "200", description = "요약",
            content = @Content(schema = @Schema(implementation = DevEvaluationStatsResponse.class)))
    ResponseEntity<DevEvaluationStatsResponse> stats();

    @Operation(summary = "평가 사진", description = "사진 바이트(올린 형식 그대로). Cache-Control: private, max-age=300.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "사진"),
            @ApiResponse(responseCode = "404", description = "사진 없음(DEV_EVAL_PHOTO_NOT_FOUND)")
    })
    ResponseEntity<byte[]> photo(String id);

    @Operation(summary = "CSV 내려받기", description = "기록마다 한 줄(사진 없음). UTF-8 BOM, CRLF. 목록과 같은 guide·userId 거르기를 받습니다.")
    @ApiResponse(responseCode = "200", description = "text/csv")
    ResponseEntity<byte[]> csv(Boolean guide, Long userId);

    @Operation(summary = "평가 지우기", description = "기록과 사진을 함께 지웁니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "지움"),
            @ApiResponse(responseCode = "404", description = "기록 없음(DEV_EVAL_NOT_FOUND)")
    })
    ResponseEntity<Void> delete(String id);
}
