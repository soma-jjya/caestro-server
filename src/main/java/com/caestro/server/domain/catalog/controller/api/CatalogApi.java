package com.caestro.server.domain.catalog.controller.api;

import com.caestro.server.domain.catalog.dto.response.CatalogBannerListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameResponse;
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

@Tag(name = "Catalog", description = "카탈로그 — 디자이너가 올린 AI 네컷 프레임과 홈 배너를 앱·웹이 내려받는다 (인증 없음)")
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

    @Operation(summary = "그림 바이트", description = "id 가 난수이고 내용이 안 바뀌므로 30일 캐시합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지"),
            @ApiResponse(responseCode = "404", description = "없음")
    })
    ResponseEntity<byte[]> image(String imageId);
}
