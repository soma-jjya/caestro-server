package com.caestro.server.domain.notification.controller.api;

import com.caestro.server.domain.notification.dto.response.NotificationPageResponse;
import com.caestro.server.domain.notification.dto.response.UnreadCountResponse;
import com.caestro.server.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Notification", description = "알림함 (contracts/notifications.md)")
public interface NotificationApi {

    @Operation(summary = "알림 목록",
            description = "내 알림을 최신순으로 돌려줍니다. 응답의 nextCursor 를 다음 요청의 cursor 로 넘기면 이어집니다(null 이면 끝). "
                    + "각 항목의 data 는 푸시의 data 와 같아서 탭 처리 규칙을 그대로 쓰면 됩니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "목록"),
            @ApiResponse(responseCode = "401", description = "토큰이 없음")
    })
    ResponseEntity<NotificationPageResponse> list(
            @Parameter(description = "이전 응답의 nextCursor. 첫 페이지면 생략") Long cursor,
            @Parameter(description = "페이지 크기. 기본 20, 최대 50") Integer size,
            CustomUserDetails userDetails);

    @Operation(summary = "안 읽은 알림 수", description = "종 아이콘의 빨간 점. 앱이 홈에 올 때마다 불러도 됩니다.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "개수")
    })
    ResponseEntity<UnreadCountResponse> unreadCount(CustomUserDetails userDetails);

    @Operation(summary = "알림 하나 읽음",
            description = "목록에서 탭했거나 푸시를 탭했을 때(푸시 data 의 notificationId). 이미 읽은 것도 204.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "읽음 처리됨"),
            @ApiResponse(responseCode = "404", description = "없거나 남의 알림")
    })
    ResponseEntity<Void> markRead(@Parameter(description = "알림 id", required = true) Long id, CustomUserDetails userDetails);

    @Operation(summary = "모두 읽음", description = "알림함을 열었을 때 한 번에 지우고 싶을 때.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "읽음 처리됨")
    })
    ResponseEntity<Void> markAllRead(CustomUserDetails userDetails);
}
