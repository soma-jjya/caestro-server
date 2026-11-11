package com.caestro.server.domain.support.controller.api;

import com.caestro.server.domain.support.dto.request.CreateSupportMessageRequest;
import com.caestro.server.domain.support.dto.request.CreateSupportTicketRequest;
import com.caestro.server.domain.support.dto.response.SupportAttachmentResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketPageResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketResponse;
import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Support", description = "앱 문의하기(#263, contracts/support.md). 로그인 계정만, 본인 문의만")
@SecurityRequirement(name = "BearerAuth")
@ApiResponse(responseCode = "403", description = "토큰 없음, 또는 게스트·탈퇴 계정(SUPPORT_LOGIN_REQUIRED)")
public interface SupportApi {

    @Operation(summary = "문의 작성", description = "분류·제목·내용은 사용자가, 앱 버전·플랫폼·OS·기기명은 앱이 자동으로. 사진은 만든 뒤 따로 올린다")
    @ApiResponse(responseCode = "201", description = "작성됨(상세 응답)")
    @ApiResponse(responseCode = "429", description = "하루 작성 한도 초과")
    ResponseEntity<SupportTicketResponse> create(CreateSupportTicketRequest request, CustomUserDetails userDetails);

    @Operation(summary = "내 문의 목록", description = "최신순. userUnread 가 true 면 안 읽은 답변이 있다. nextCursor 를 다음 요청의 cursor 로(null 이면 끝)")
    ResponseEntity<SupportTicketPageResponse> list(
            @Parameter(description = "이전 응답의 nextCursor. 첫 페이지면 생략") Long cursor,
            @Parameter(description = "페이지 크기. 기본 20, 최대 50") Integer size,
            CustomUserDetails userDetails);

    @Operation(summary = "문의 상세", description = "메시지(시간순)와 사진. 열면 안 읽음 표시가 사라진다")
    @ApiResponse(responseCode = "404", description = "없거나 남의 문의")
    ResponseEntity<SupportTicketResponse> get(Long id, CustomUserDetails userDetails);

    @Operation(summary = "이어 쓰기", description = "답변 뒤 추가 질문. 닫힌 문의도 다시 열린다(OPEN)")
    @ApiResponse(responseCode = "429", description = "하루 메시지 한도 초과")
    ResponseEntity<SupportTicketResponse> addMessage(Long id, CreateSupportMessageRequest request, CustomUserDetails userDetails);

    @Operation(summary = "사진 추가", description = "본문이 이미지 바이트(요청당 1장). Content-Type 은 image/jpeg·image/png·octet-stream, 형식은 바이트로 판정. 문의당 최대 5장, 장당 5MB")
    @ApiResponse(responseCode = "201", description = "추가됨")
    @ApiResponse(responseCode = "400", description = "JPEG/PNG 가 아님, 또는 이미 5장")
    @ApiResponse(responseCode = "413", description = "5MB 초과")
    ResponseEntity<SupportAttachmentResponse> addAttachment(Long id, byte[] body, String contentType, CustomUserDetails userDetails);

    @Operation(summary = "사진 삭제")
    @ApiResponse(responseCode = "204", description = "삭제됨")
    @ApiResponse(responseCode = "404", description = "없는 사진")
    ResponseEntity<Void> deleteAttachment(Long id, Long attachmentId, CustomUserDetails userDetails);

    @Operation(summary = "사진 보기", description = "S3 면 짧은 수명의 서명 주소로 302(캐시 금지), 아니면 바이트")
    @ApiResponse(responseCode = "302", description = "서명 주소로")
    @ApiResponse(responseCode = "200", description = "이미지 바이트")
    ResponseEntity<byte[]> attachment(Long id, Long attachmentId, CustomUserDetails userDetails);
}
