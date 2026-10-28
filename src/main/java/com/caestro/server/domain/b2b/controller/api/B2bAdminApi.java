package com.caestro.server.domain.b2b.controller.api;

import com.caestro.server.domain.b2b.dto.request.*;
import com.caestro.server.domain.b2b.dto.response.B2bProjectResponse;
import com.caestro.server.domain.b2b.dto.response.B2bSlugCheckResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;

@Tag(name = "B2B Admin", description = "축제·매장 프로젝트 관리. FE 서버만 X-Studio-Key를 전송한다.")
@SecurityRequirement(name = "StudioKey")
@ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치 또는 권한 없음")
public interface B2bAdminApi {
    @Operation(summary = "프로젝트 목록", description = "updatedAt 내림차순, 현재 프레임 포함")
    ResponseEntity<List<B2bProjectResponse>> list();

    @Operation(summary = "프로젝트 조회")
    ResponseEntity<B2bProjectResponse> get(String id);

    @Operation(summary = "프로젝트 생성", description = "DRAFT, 프레임 없음. 중복 slug는 409.")
    @ApiResponse(responseCode = "201", description = "생성됨")
    ResponseEntity<B2bProjectResponse> create(UpsertB2bProjectRequest request);

    @Operation(summary = "프로젝트 기본 정보 교체", description = "name/slug/description. 최초 공개 후 slug 변경은 409.")
    ResponseEntity<B2bProjectResponse> update(String id, UpsertB2bProjectRequest request);

    @Operation(summary = "프레임 목록 전체 교체", description = "최대 3개. 배열 순서가 displayOrder. id 생략은 생성, 누락은 선택 목록에서 삭제. "
            + "imageId는 catalog/studio/images 업로드 id. 초안/중지는 0~3개 및 미완성 설정 허용, LIVE는 모든 프레임이 유효한 1~3개 유지. 기존 네컷 스냅샷은 유지.")
    ResponseEntity<B2bProjectResponse> replaceFrames(String id, ReplaceB2bFramesRequest request);

    @Operation(summary = "최초 공개", description = "DRAFT → LIVE. 프레임 1~3개가 필요하며, 모두 이미지와 창 4개를 가져야 함. 0개·미완성 프레임 또는 상태 미충족은 409.")
    ResponseEntity<B2bProjectResponse> publish(String id);

    @Operation(summary = "일시중지", description = "LIVE → PAUSED. 신규 참여를 막고 기존 네컷은 만료까지 유지.")
    ResponseEntity<B2bProjectResponse> pause(String id);

    @Operation(summary = "공개 재개", description = "PAUSED → LIVE. 모두 이미지와 창 4개를 가진 프레임 1~3개인지 재검사, publishedAt 유지. 0개·미완성 프레임 또는 상태 미충족은 409.")
    ResponseEntity<B2bProjectResponse> resume(String id);

    @Operation(summary = "프로젝트 복제", description = "설정만 새 id의 DRAFT로 복제. 참여자·사진은 복사하지 않음.")
    @ApiResponse(responseCode = "201", description = "복제됨")
    ResponseEntity<B2bProjectResponse> duplicate(String id, DuplicateB2bProjectRequest request);

    @Operation(summary = "slug 중복 확인", description = "reason: format/reserved/duplicate. exclude는 편집 중인 프로젝트 id.")
    ResponseEntity<B2bSlugCheckResponse> slugCheck(String slug, String exclude);
}
