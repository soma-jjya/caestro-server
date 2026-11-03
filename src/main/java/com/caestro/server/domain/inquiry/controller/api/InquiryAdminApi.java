package com.caestro.server.domain.inquiry.controller.api;

import com.caestro.server.domain.inquiry.dto.request.UpdateInquiryRequest;
import com.caestro.server.domain.inquiry.dto.response.InquiryCountResponse;
import com.caestro.server.domain.inquiry.dto.response.InquiryPageResponse;
import com.caestro.server.domain.inquiry.dto.response.InquiryResponse;
import com.caestro.server.domain.inquiry.enums.InquiryStatus;
import com.caestro.server.domain.inquiry.enums.InquiryType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Inquiry Admin", description = "B2B 문의 관리. 관리자 페이지 서버만 X-Studio-Key 를 보낸다(ADMIN JWT 도 가능)")
@SecurityRequirement(name = "StudioKey")
@ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치 또는 권한 없음")
public interface InquiryAdminApi {

    @Operation(summary = "문의 목록", description = "최신순. status·type 생략 시 전체. 응답의 nextCursor 를 다음 요청의 cursor 로(null 이면 끝)")
    ResponseEntity<InquiryPageResponse> list(
            @Parameter(description = "상태 필터") InquiryStatus status,
            @Parameter(description = "유형 필터") InquiryType type,
            @Parameter(description = "이전 응답의 nextCursor. 첫 페이지면 생략") Long cursor,
            @Parameter(description = "페이지 크기. 기본 20, 최대 50") Integer size);

    @Operation(summary = "문의 상세")
    @ApiResponse(responseCode = "404", description = "없는 문의")
    ResponseEntity<InquiryResponse> get(Long id);

    @Operation(summary = "상태별 개수", description = "관리자 탭 배지용. status 생략 시 전체 개수")
    ResponseEntity<InquiryCountResponse> count(@Parameter(description = "상태. 보통 NEW") InquiryStatus status);

    @Operation(summary = "문의 수정(부분)",
            description = "status·adminMemo·linkedProjectId 중 보낸 것만 바꾼다. 상태가 NEW 를 처음 벗어날 때 handledAt 기록. "
                    + "linkedProjectId 는 계약 뒤 만든 B2B 프로젝트 id, 빈 문자열이면 연결 해제")
    @ApiResponse(responseCode = "404", description = "없는 문의, 또는 없는 프로젝트 id")
    ResponseEntity<InquiryResponse> update(Long id, UpdateInquiryRequest request);
}
