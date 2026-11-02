package com.caestro.server.domain.notification.controller;

import com.caestro.server.domain.notification.controller.api.NotificationApi;
import com.caestro.server.domain.notification.dto.response.NotificationPageResponse;
import com.caestro.server.domain.notification.dto.response.UnreadCountResponse;
import com.caestro.server.domain.notification.service.NotificationService;
import com.caestro.server.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController implements NotificationApi {

    private final NotificationService notificationService;

    @GetMapping
    @Override
    public ResponseEntity<NotificationPageResponse> list(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(notificationService.list(userDetails.getUserId(), cursor, size));
    }

    @GetMapping("/unread-count")
    @Override
    public ResponseEntity<UnreadCountResponse> unreadCount(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(notificationService.unreadCount(userDetails.getUserId()));
    }

    @PatchMapping("/{id}/read")
    @Override
    public ResponseEntity<Void> markRead(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails userDetails) {
        notificationService.markRead(userDetails.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/read-all")
    @Override
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal CustomUserDetails userDetails) {
        notificationService.markAllRead(userDetails.getUserId());
        return ResponseEntity.noContent().build();
    }
}
