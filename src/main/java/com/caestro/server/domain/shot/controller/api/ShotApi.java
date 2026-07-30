package com.caestro.server.domain.shot.controller.api;

import com.caestro.server.domain.shot.dto.request.CreateShotRequest;
import com.caestro.server.domain.shot.dto.response.ShotResponse;
import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

@Tag(name = "Shot", description = "촬영 결과물 API")
public interface ShotApi {

    @Operation(
            summary = "촬영 결과물 저장",
            description = "촬영 결과물 메타데이터를 저장합니다. director는 토큰에서 추출하며, "
                    + "camera는 세션의 촬영자를 스냅샷으로 복사합니다. 이미지 파일은 업로드하지 않습니다."
    )
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "저장 성공",
                    content = @Content(schema = @Schema(implementation = ShotResponse.class))),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 입력값 (mode/해상도/용량/위치)"),
            @ApiResponse(responseCode = "401", description = "토큰이 없음"),
            @ApiResponse(responseCode = "404", description = "sessionCode에 해당하는 세션 없음")
    })
    ResponseEntity<ShotResponse> createShot(CreateShotRequest request, CustomUserDetails userDetails);

    @Operation(
            summary = "내 촬영 이력 조회",
            description = "인증된 사용자 본인의 촬영 이력을 최신순으로 페이지 단위 조회합니다."
    )
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "토큰이 없음")
    })
    ResponseEntity<Page<ShotResponse>> getMyShots(Pageable pageable, CustomUserDetails userDetails);

    @Operation(
            summary = "촬영 결과물 단건 조회",
            description = "촬영 결과물 상세를 조회합니다. 본인 소유가 아니면 접근이 차단됩니다."
    )
    @SecurityRequirement(name = "BearerAuth")
    @Parameter(name = "id", description = "조회할 촬영 결과물 ID", in = ParameterIn.PATH, required = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = ShotResponse.class))),
            @ApiResponse(responseCode = "401", description = "토큰이 없음"),
            @ApiResponse(responseCode = "403", description = "본인 소유가 아님"),
            @ApiResponse(responseCode = "404", description = "결과물을 찾을 수 없음")
    })
    ResponseEntity<ShotResponse> getShot(Long id, CustomUserDetails userDetails);
}
