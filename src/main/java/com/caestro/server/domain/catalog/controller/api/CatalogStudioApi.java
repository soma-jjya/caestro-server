package com.caestro.server.domain.catalog.controller.api;

import com.caestro.server.domain.catalog.dto.request.UpsertBannerRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertFrameRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogBannerResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogImageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Catalog Studio", description = "디자이너 스튜디오 — 프레임·배너 올리기·수정·숨김·삭제. 헤더 X-Studio-Key(설정 catalog.studio-key) 필요")
public interface CatalogStudioApi {

    @Operation(summary = "그림 올리기",
            description = "본문은 이미지 바이트(image/png 또는 image/jpeg, 최대 8MB). 폭·높이를 읽어 id 와 함께 돌려주며, 프레임·배너를 만들 때 imageId 로 묶습니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "저장됨",
                    content = @Content(schema = @Schema(implementation = CatalogImageResponse.class))),
            @ApiResponse(responseCode = "400", description = "PNG/JPEG 가 아니거나 읽을 수 없음"),
            @ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치"),
            @ApiResponse(responseCode = "413", description = "8MB 초과")
    })
    ResponseEntity<CatalogImageResponse> uploadImage(byte[] body, String contentType);

    @Operation(summary = "프레임 만들기", description = "title.ko 1..40자, windows 는 정확히 4개(비율 사각형 + 회전각).")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "만들어짐",
                    content = @Content(schema = @Schema(implementation = CatalogFrameResponse.class))),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음(CATALOG_INVALID_FRAME)"),
            @ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치")
    })
    ResponseEntity<CatalogFrameResponse> createFrame(UpsertFrameRequest request);

    @Operation(summary = "프레임 수정", description = "보낸 필드만 바뀝니다. imageId 를 바꾸면 이전 그림은 지웁니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "바뀐 프레임"),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음"),
            @ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    ResponseEntity<CatalogFrameResponse> updateFrame(String id, UpsertFrameRequest request);

    @Operation(summary = "프레임 삭제", description = "그림도 함께 지웁니다. 이 프레임을 쓰던 세션은 클라이언트가 기본 프레임으로 대신 그립니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "지워짐"),
            @ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    ResponseEntity<Void> deleteFrame(String id);

    @Operation(summary = "배너 만들기", description = "title.ko 1..60자. action 은 poseGuide|fourCut|fillTogether|frameLibrary|url, url 이면 linkUrl(https) 필요.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "만들어짐",
                    content = @Content(schema = @Schema(implementation = CatalogBannerResponse.class))),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음(CATALOG_INVALID_BANNER)")
    })
    ResponseEntity<CatalogBannerResponse> createBanner(UpsertBannerRequest request);

    @Operation(summary = "배너 수정", description = "보낸 필드만 바뀝니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "바뀐 배너"),
            @ApiResponse(responseCode = "404", description = "배너 없음")
    })
    ResponseEntity<CatalogBannerResponse> updateBanner(String id, UpsertBannerRequest request);

    @Operation(summary = "배너 삭제", description = "그림도 함께 지웁니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "지워짐"),
            @ApiResponse(responseCode = "404", description = "배너 없음")
    })
    ResponseEntity<Void> deleteBanner(String id);
}
