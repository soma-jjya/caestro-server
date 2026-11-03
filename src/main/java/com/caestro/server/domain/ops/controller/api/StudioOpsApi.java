package com.caestro.server.domain.ops.controller.api;

import com.caestro.server.domain.ops.dto.response.OpsStatsResponse;
import com.caestro.server.domain.ops.dto.response.OpsSubscriptionPageResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserDetailResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserPageResponse;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Studio Ops", description = "운영 조회(읽기 전용): 가입 경로·마지막 로그인·Pro 구독. peakpic.app/studio 가 X-Studio-Key 로 부른다(ADMIN JWT 도 가능)")
@SecurityRequirement(name = "StudioKey")
@ApiResponse(responseCode = "403", description = "스튜디오 키 없음/불일치 또는 권한 없음")
public interface StudioOpsApi {

    @Operation(summary = "요약 숫자",
            description = "가입 경로별 사용자 수(탈퇴 제외)와 탈퇴, 오늘·7일(서울 자정 기준) 신규와 활성(마지막 로그인), "
                    + "지금 활성인 구독 수와 스토어별·해지 예약")
    ResponseEntity<OpsStatsResponse> stats();

    @Operation(summary = "사용자 목록", description = "최신순. 응답의 nextCursor 를 다음 요청의 cursor 로(null 이면 끝)")
    ResponseEntity<OpsUserPageResponse> users(
            @Parameter(description = "숫자만이면 사용자 id, 아니면 닉네임 일부") String q,
            @Parameter(description = "kakao · google · apple · guest · deleted") String provider,
            @Parameter(description = "true 지금 Pro / false Pro 아님") Boolean pro,
            @Parameter(description = "이전 응답의 nextCursor. 첫 페이지면 생략") Long cursor,
            @Parameter(description = "페이지 크기. 기본 20, 최대 50") Integer size);

    @Operation(summary = "사용자 상세", description = "가린 소셜·기기 id, 알림 받는 기기 수, 구독 이력 전부(만료가 늦은 것부터)")
    @ApiResponse(responseCode = "404", description = "없는 사용자")
    ResponseEntity<OpsUserDetailResponse> user(Long id);

    @Operation(summary = "구독 목록", description = "최신순. 구매 토큰은 싣지 않는다. active 는 ACTIVE 이고 만료 전인지")
    ResponseEntity<OpsSubscriptionPageResponse> subscriptions(
            @Parameter(description = "상태 필터") SubscriptionStatus status,
            @Parameter(description = "스토어 필터") SubscriptionStore store,
            @Parameter(description = "이전 응답의 nextCursor. 첫 페이지면 생략") Long cursor,
            @Parameter(description = "페이지 크기. 기본 20, 최대 50") Integer size);
}
