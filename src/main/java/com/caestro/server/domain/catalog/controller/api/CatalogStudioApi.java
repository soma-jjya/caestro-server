package com.caestro.server.domain.catalog.controller.api;

import com.caestro.server.domain.catalog.dto.request.UpsertBannerRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertFrameRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertPoseOverrideRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertPoseUploadRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogBannerResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogImageResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseOverrideResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseUploadResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogTrashResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Catalog Studio", description = "디자이너 스튜디오 — 프레임·배너·포즈 사진 올리기·수정·숨김, 휴지통(7일 보관·복구·완전 삭제). 헤더 X-Studio-Key(설정 catalog.studio-key) 필요")
public interface CatalogStudioApi {

    @Operation(summary = "그림 올리기",
            description = "본문은 이미지 바이트(image/png 또는 image/jpeg, 최대 8MB). 폭·높이를 읽어 id 와 함께 돌려주며, 프레임·배너·포즈 사진을 만들 때 imageId 로 묶습니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "저장됨",
                    content = @Content(schema = @Schema(implementation = CatalogImageResponse.class))),
            @ApiResponse(responseCode = "400", description = "PNG/JPEG 가 아니거나 읽을 수 없음"),
            @ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치"),
            @ApiResponse(responseCode = "413", description = "8MB 초과")
    })
    ResponseEntity<CatalogImageResponse> uploadImage(byte[] body, String contentType);

    // ── 프레임 ──

    @Operation(summary = "프레임 만들기", description = "title.ko 1..40자, windows 는 정확히 4개(비율 사각형 + 회전각).")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "만들어짐",
                    content = @Content(schema = @Schema(implementation = CatalogFrameResponse.class))),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음(CATALOG_INVALID_FRAME)"),
            @ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치")
    })
    ResponseEntity<CatalogFrameResponse> createFrame(UpsertFrameRequest request);

    @Operation(summary = "프레임 수정", description = "보낸 필드만 바뀝니다. imageId 를 바꾸면 이전 그림은 지웁니다. 휴지통에 있는 프레임은 404.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "바뀐 프레임"),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음"),
            @ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    ResponseEntity<CatalogFrameResponse> updateFrame(String id, UpsertFrameRequest request);

    @Operation(summary = "프레임 삭제(휴지통)", description = "목록에서 바로 빠지고 휴지통에 catalog.trash-days(7일) 동안 남습니다. id 로는 계속 열립니다(세션이 참조 중일 수 있음).")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "휴지통으로 감"),
            @ApiResponse(responseCode = "404", description = "프레임 없음(이미 휴지통 포함)")
    })
    ResponseEntity<Void> deleteFrame(String id);

    @Operation(summary = "프레임 복구", description = "휴지통에서 꺼냅니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "복구된 프레임"),
            @ApiResponse(responseCode = "404", description = "휴지통에 없음")
    })
    ResponseEntity<CatalogFrameResponse> restoreFrame(String id);

    // ── 배너 ──

    @Operation(summary = "배너 만들기", description = "title.ko 1..60자. action 은 poseGuide|fourCut|fillTogether|frameLibrary|url, url 이면 linkUrl(https) 필요.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "만들어짐",
                    content = @Content(schema = @Schema(implementation = CatalogBannerResponse.class))),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음(CATALOG_INVALID_BANNER)")
    })
    ResponseEntity<CatalogBannerResponse> createBanner(UpsertBannerRequest request);

    @Operation(summary = "배너 수정", description = "보낸 필드만 바뀝니다. 마지막으로 보이는 배너를 숨기면 409(홈 배너는 최소 한 개).")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "바뀐 배너"),
            @ApiResponse(responseCode = "404", description = "배너 없음"),
            @ApiResponse(responseCode = "409", description = "마지막으로 보이는 배너(CATALOG_LAST_BANNER)")
    })
    ResponseEntity<CatalogBannerResponse> updateBanner(String id, UpsertBannerRequest request);

    @Operation(summary = "배너 삭제(휴지통)", description = "휴지통에 7일 동안 남습니다. 마지막으로 보이는 배너는 409.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "휴지통으로 감"),
            @ApiResponse(responseCode = "404", description = "배너 없음"),
            @ApiResponse(responseCode = "409", description = "마지막으로 보이는 배너(CATALOG_LAST_BANNER)")
    })
    ResponseEntity<Void> deleteBanner(String id);

    @Operation(summary = "배너 복구", description = "휴지통에서 꺼냅니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "복구된 배너"),
            @ApiResponse(responseCode = "404", description = "휴지통에 없음")
    })
    ResponseEntity<CatalogBannerResponse> restoreBanner(String id);

    // ── 포즈추천 ──

    @Operation(summary = "파이프라인 샷 덧씌우기",
            description = "보낸 필드만 바뀝니다: hidden(앱에서 숨김), labels(장소·성별·인종·계절, 통째로 교체), deleted(true 면 휴지통, false 면 꺼냄). "
                    + "S3 의 파일은 건드리지 않으며, 덧씌울 것이 하나도 없으면 행이 지워집니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "덧씌운 값",
                    content = @Content(schema = @Schema(implementation = CatalogPoseOverrideResponse.class))),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음(CATALOG_INVALID_POSE)")
    })
    ResponseEntity<CatalogPoseOverrideResponse> upsertPoseOverride(String version, String shot, UpsertPoseOverrideRequest request);

    @Operation(summary = "파이프라인 샷 복구", description = "휴지통에서 꺼내 다시 보이게 합니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "복구된 값"),
            @ApiResponse(responseCode = "404", description = "휴지통에 없음")
    })
    ResponseEntity<CatalogPoseOverrideResponse> restorePoseOverride(String version, String shot);

    @Operation(summary = "포즈 사진 올리기", description = "labels.장소 필수(≤20자), 성별 남자|여자, 계절 봄·가을|여름|겨울, 인종 ≤20자(앱에 안 보임). imageId 는 먼저 올린 그림.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "만들어짐",
                    content = @Content(schema = @Schema(implementation = CatalogPoseUploadResponse.class))),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음(CATALOG_INVALID_POSE)")
    })
    ResponseEntity<CatalogPoseUploadResponse> createPoseUpload(UpsertPoseUploadRequest request);

    @Operation(summary = "포즈 사진 수정", description = "보낸 필드만 바뀝니다(labels 는 통째로 교체). imageId 를 바꾸면 이전 그림은 지웁니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "바뀐 사진"),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음"),
            @ApiResponse(responseCode = "404", description = "사진 없음")
    })
    ResponseEntity<CatalogPoseUploadResponse> updatePoseUpload(String id, UpsertPoseUploadRequest request);

    @Operation(summary = "포즈 사진 삭제(휴지통)", description = "휴지통에 7일 동안 남습니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "휴지통으로 감"),
            @ApiResponse(responseCode = "404", description = "사진 없음")
    })
    ResponseEntity<Void> deletePoseUpload(String id);

    @Operation(summary = "포즈 사진 복구", description = "휴지통에서 꺼냅니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "복구된 사진"),
            @ApiResponse(responseCode = "404", description = "휴지통에 없음")
    })
    ResponseEntity<CatalogPoseUploadResponse> restorePoseUpload(String id);

    // ── 휴지통 ──

    @Operation(summary = "휴지통", description = "지운 지 7일 안의 프레임·배너·포즈 사진·덧씌우기. 각 항목에 deletedAt·purgeAt 이 붙습니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "휴지통",
                    content = @Content(schema = @Schema(implementation = CatalogTrashResponse.class)))
    })
    ResponseEntity<CatalogTrashResponse> trash();

    @Operation(summary = "프레임 완전 삭제", description = "휴지통에서 그림과 함께 지웁니다. 되돌릴 수 없습니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "지워짐"),
            @ApiResponse(responseCode = "404", description = "휴지통에 없음")
    })
    ResponseEntity<Void> purgeFrame(String id);

    @Operation(summary = "배너 완전 삭제", description = "휴지통에서 그림과 함께 지웁니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "지워짐"),
            @ApiResponse(responseCode = "404", description = "휴지통에 없음")
    })
    ResponseEntity<Void> purgeBanner(String id);

    @Operation(summary = "포즈 사진 완전 삭제", description = "휴지통에서 그림과 함께 지웁니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "지워짐"),
            @ApiResponse(responseCode = "404", description = "휴지통에 없음")
    })
    ResponseEntity<Void> purgePoseUpload(String id);
}
