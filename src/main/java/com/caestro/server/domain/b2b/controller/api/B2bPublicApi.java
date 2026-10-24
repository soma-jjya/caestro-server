package com.caestro.server.domain.b2b.controller.api;

import com.caestro.server.domain.b2b.dto.request.CreateB2bFillRequest;
import com.caestro.server.domain.b2b.dto.response.B2bFillResponse;
import com.caestro.server.domain.b2b.dto.response.B2bPublicProjectResponse;
import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "B2B Public", description = "축제·매장 선택 페이지와 공유 네컷. 사진 채우기는 기존 /fills API를 사용.")
public interface B2bPublicApi {
    @Operation(summary = "공개 프로젝트", description = "키/로그인 불필요. LIVE만 200, DRAFT/PAUSED/없는 slug는 404.")
    ResponseEntity<B2bPublicProjectResponse> project(String slug);

    @Operation(summary = "B2B 네컷 생성", description = "선택 화면에서 받은 frameId와 frameVersion 필요. frame은 불변 스냅샷, fill은 기존 네컷 상태. "
            + "shareUrl은 peakpic.app/{slug}/{code}. 기존 생성 한도 적용.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponse(responseCode = "201", description = "공유 링크 생성")
    @ApiResponse(responseCode = "409", description = "프레임 버전 변경 — 선택 화면 새로고침 필요")
    ResponseEntity<B2bFillResponse> create(String slug, CreateB2bFillRequest request,
                                         @Parameter(hidden = true) CustomUserDetails user);

    @Operation(summary = "B2B 네컷 조회", description = "기존 게스트/회원 JWT. 소속 불일치는 404, 만료는 410. PAUSED도 기존 세션 조회 가능.")
    @SecurityRequirement(name = "BearerAuth")
    ResponseEntity<B2bFillResponse> get(String slug, String code, @Parameter(hidden = true) CustomUserDetails user);

    @Operation(summary = "B2B 프레임 이미지", description = "카탈로그와 별도 보관된 불변 사본. 키/로그인 불필요.")
    ResponseEntity<byte[]> image(String id);
}
