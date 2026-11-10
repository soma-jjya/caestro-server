package com.caestro.server.domain.catalog.controller.api;

import com.caestro.server.domain.catalog.dto.request.UpsertRecommendRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendFileResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendManifest;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Catalog Recommend", description = "포즈추천 알고리즘 설정 — 장소 읽기 문턱값·점수 가중치·장소→풀 매핑과 장소 헤드·모델을 버전으로 만들고 하나를 게시한다. "
        + "게시본 읽기는 인증 없음, 나머지는 X-Studio-Key (contracts/recommend-config.md)")
public interface CatalogRecommendApi {

    @Operation(summary = "게시본 (앱)",
            description = "게시한 버전 하나. 없으면 {} (앱은 번들 기본값). params 안의 키는 모두 선택, head·model 이 null 이면 앱 번들 것. "
                    + "ETag 를 내려주며 If-None-Match 가 같으면 304. 게시·롤백·게시 내리기마다 ETag 가 바뀝니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "게시본 또는 {}",
                    content = @Content(schema = @Schema(implementation = CatalogRecommendManifest.class))),
            @ApiResponse(responseCode = "304", description = "바뀐 것이 없음")
    })
    ResponseEntity<Object> manifest(String ifNoneMatch);

    @Operation(summary = "버전 목록", description = "만든 순서 최신부터. activeId 는 게시 중인 버전(없으면 null).")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "목록",
                    content = @Content(schema = @Schema(implementation = CatalogRecommendListResponse.class))),
            @ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치")
    })
    ResponseEntity<CatalogRecommendListResponse> list();

    @Operation(summary = "버전 만들기",
            description = "name 1..60자, note ≤ 500자, params(place·scoring·placeToPool, 모르는 키는 400), headId·modelId(null 이면 앱 번들). 게시되지 않은 채로 만들어집니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "만들어짐",
                    content = @Content(schema = @Schema(implementation = CatalogRecommendResponse.class))),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음(RECOMMEND_INVALID, 무엇이 틀렸는지 message 에)"),
            @ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치")
    })
    ResponseEntity<CatalogRecommendResponse> create(UpsertRecommendRequest request);

    @Operation(summary = "버전 고치기", description = "만들기와 같은 본문으로 통째로 바꿉니다. 게시 중인 버전은 409(복제해서 고치세요).")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "바뀐 버전"),
            @ApiResponse(responseCode = "400", description = "값이 올바르지 않음(RECOMMEND_INVALID)"),
            @ApiResponse(responseCode = "404", description = "버전 없음(RECOMMEND_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "게시 중(RECOMMEND_ACTIVE_LOCKED)")
    })
    ResponseEntity<CatalogRecommendResponse> update(@Parameter(description = "버전 id (rc_…)") String id, UpsertRecommendRequest request);

    @Operation(summary = "게시", description = "이 버전만 게시하고 나머지는 모두 내립니다. publishedAt = 지금. 옛 버전을 게시하면 롤백입니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "게시한 버전"),
            @ApiResponse(responseCode = "404", description = "버전 없음")
    })
    ResponseEntity<CatalogRecommendResponse> publish(String id);

    @Operation(summary = "게시 내리기", description = "게시본이 없어집니다. 앱은 번들 기본값으로 돌아갑니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponse(responseCode = "204", description = "내림(게시본이 없었어도 204)")
    ResponseEntity<Void> unpublish();

    @Operation(summary = "버전 지우기", description = "휴지통 없이 지웁니다. 아무 버전도 안 쓰게 된 헤드·모델 파일도 지웁니다. 게시 중이면 409.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "지움"),
            @ApiResponse(responseCode = "404", description = "버전 없음"),
            @ApiResponse(responseCode = "409", description = "게시 중(RECOMMEND_ACTIVE_LOCKED)")
    })
    ResponseEntity<Void> delete(String id);

    @Operation(summary = "헤드·모델 파일 올리기",
            description = "kind=head: Content-Type application/json, 최대 5MB, 모양 검증(classes 2..20, places365_bucket 정수 365개, feature_dim, "
                    + "weights classes×(feature_dim+classes), bias classes). kind=model: application/octet-stream, 최대 32MB, 비어 있지 않음. "
                    + "버전을 만들 때 id 를 headId·modelId 로 묶습니다. 하루 안에 어느 버전에도 묶지 않으면 정리 배치가 지웁니다.")
    @SecurityRequirement(name = "StudioKey")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "저장됨",
                    content = @Content(schema = @Schema(implementation = CatalogRecommendFileResponse.class))),
            @ApiResponse(responseCode = "400", description = "kind 가 틀림(RECOMMEND_INVALID), 헤드 모양이 틀림(RECOMMEND_HEAD_INVALID), 모델이 비어 있음(RECOMMEND_MODEL_INVALID)"),
            @ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치"),
            @ApiResponse(responseCode = "411", description = "Content-Length 없음"),
            @ApiResponse(responseCode = "413", description = "상한 초과(RECOMMEND_FILE_TOO_LARGE)")
    })
    ResponseEntity<CatalogRecommendFileResponse> uploadFile(@Parameter(description = "head | model") String kind, byte[] body, String contentType);
}
