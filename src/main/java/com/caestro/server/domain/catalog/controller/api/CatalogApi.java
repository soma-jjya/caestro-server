package com.caestro.server.domain.catalog.controller.api;

import com.caestro.server.domain.catalog.dto.response.CatalogBannerListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

@Tag(name = "Catalog", description = "카탈로그 — 디자이너가 올린 AI 네컷 프레임·홈 배너·포즈추천 덧씌우기를 앱·웹이 내려받는다 (인증 없음)")
public interface CatalogApi {

    @Operation(summary = "프레임 목록",
            description = "숨김이 아닌 프레임을 sortOrder·createdAt 순으로. ETag 를 내려주며 If-None-Match 가 같으면 304. "
                    + "스튜디오 키(X-Studio-Key)와 함께 all=true 를 붙이면 숨김도 포함합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "목록",
                    content = @Content(schema = @Schema(implementation = CatalogFrameListResponse.class))),
            @ApiResponse(responseCode = "304", description = "바뀐 것이 없음")
    })
    ResponseEntity<CatalogFrameListResponse> frames(boolean all, String ifNoneMatch, HttpServletRequest request);

    @Operation(summary = "프레임 하나", description = "숨김이어도 돌려줍니다(세션·미러링이 참조 중일 수 있음).")
    @Parameter(name = "id", description = "프레임 id (af_…)", in = ParameterIn.PATH, required = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "프레임",
                    content = @Content(schema = @Schema(implementation = CatalogFrameResponse.class))),
            @ApiResponse(responseCode = "404", description = "없음")
    })
    ResponseEntity<CatalogFrameResponse> frame(String id);

    @Operation(summary = "홈 배너 목록",
            description = "숨김이 아닌 배너를 sortOrder·createdAt 순으로. ETag/If-None-Match 는 프레임 목록과 같습니다. 비어 있으면 앱은 번들 배너를 보입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "목록",
                    content = @Content(schema = @Schema(implementation = CatalogBannerListResponse.class))),
            @ApiResponse(responseCode = "304", description = "바뀐 것이 없음")
    })
    ResponseEntity<CatalogBannerListResponse> banners(boolean all, String ifNoneMatch, HttpServletRequest request);

    @Operation(summary = "포즈추천 덧씌우기·스튜디오 업로드",
            description = "overrides: 파이프라인 샷의 숨김·추가 라벨(숨김이거나 라벨이 있는 것만; 휴지통은 hidden=true). "
                    + "uploads: 디자이너가 올린 AI 사진(숨김·휴지통 제외). ETag/If-None-Match 지원. 스튜디오 키 + all=true 면 숨긴 업로드도 포함. "
                    + "라벨 키는 장소·성별·인종·계절(한국어 토큰); 인종은 앱에 보이지 않는 내부 분류입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "덧씌우기와 업로드",
                    content = @Content(schema = @Schema(implementation = CatalogPoseResponse.class))),
            @ApiResponse(responseCode = "304", description = "바뀐 것이 없음")
    })
    ResponseEntity<CatalogPoseResponse> pose(boolean all, String ifNoneMatch, HttpServletRequest request);

    @Operation(summary = "그림·동영상 바이트", description = "id 가 난수이고 내용이 안 바뀌므로 30일 캐시합니다. 동영상(video/mp4)은 Range 요청을 받습니다(206, Accept-Ranges: bytes).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지 또는 동영상"),
            @ApiResponse(responseCode = "206", description = "동영상의 요청한 범위"),
            @ApiResponse(responseCode = "404", description = "없음")
    })
    ResponseEntity<?> image(String imageId);
}
