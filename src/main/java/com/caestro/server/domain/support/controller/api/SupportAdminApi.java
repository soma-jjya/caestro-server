package com.caestro.server.domain.support.controller.api;

import com.caestro.server.domain.support.dto.request.CreateSupportMessageRequest;
import com.caestro.server.domain.support.dto.request.UpdateSupportTicketRequest;
import com.caestro.server.domain.support.dto.response.SupportAdminTicketResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketCountResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketPageResponse;
import com.caestro.server.domain.support.enums.SupportCategory;
import com.caestro.server.domain.support.enums.SupportStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Support Admin", description = "앱 문의 관리(#263). 관리자 페이지 서버만 X-Studio-Key 를 보낸다(ADMIN JWT 도 가능)")
@SecurityRequirement(name = "StudioKey")
@ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치 또는 권한 없음")
public interface SupportAdminApi {

    @Operation(summary = "문의 목록", description = "최신순. 미답변만 보려면 status=OPEN. nextCursor 를 다음 요청의 cursor 로(null 이면 끝)")
    ResponseEntity<SupportTicketPageResponse> list(
            @Parameter(description = "상태 필터") SupportStatus status,
            @Parameter(description = "분류 필터") SupportCategory category,
            @Parameter(description = "이전 응답의 nextCursor. 첫 페이지면 생략") Long cursor,
            @Parameter(description = "페이지 크기. 기본 20, 최대 50") Integer size);

    @Operation(summary = "상태별 개수", description = "사이드바 배지용. 보통 status=OPEN. 생략하면 전체")
    ResponseEntity<SupportTicketCountResponse> count(SupportStatus status);

    @Operation(summary = "문의 상세", description = "스레드·사진·보낸 사람(탈퇴했으면 withdrawn)·기기 정보·메모")
    @ApiResponse(responseCode = "404", description = "없는 문의")
    ResponseEntity<SupportAdminTicketResponse> get(Long id);

    @Operation(summary = "답변", description = "상태 ANSWERED, 사용자에게 알림함 저장 + 푸시 support_reply(커밋 뒤)")
    @ApiResponse(responseCode = "201", description = "답변됨(상세 응답)")
    ResponseEntity<SupportAdminTicketResponse> reply(Long id, CreateSupportMessageRequest request);

    @Operation(summary = "문의 수정(부분)", description = "status 는 CLOSED(닫기)·OPEN(다시 열기)만, adminMemo 는 빈 문자열이면 지움")
    ResponseEntity<SupportAdminTicketResponse> update(Long id, UpdateSupportTicketRequest request);

    @Operation(summary = "사진 보기", description = "S3 면 서명 주소로 302, 아니면 바이트")
    ResponseEntity<byte[]> attachment(Long id, Long attachmentId);
}
