package com.caestro.server.domain.subscription.controller;

import com.caestro.server.domain.subscription.appstore.AppStoreUnavailableException;
import com.caestro.server.domain.subscription.dto.request.AppStoreNotificationRequest;
import com.caestro.server.domain.subscription.service.AppStoreSubscriptionService;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App Store Server Notifications V2 수신. App Store Connect 에 등록한 주소로 Apple 이 보낸다.
 *
 * <p>인증: 등록한 URL 에 넣은 ?token= 값(apple.app-store.notification-token)과 비교한다. 비어 있으면 모두 403.
 * 알림 내용은 믿지 않고 거래 번호만 꺼내 Apple 에 다시 묻는다. 그래서 위조된 알림이 와도 할 수 있는 일은 재확인뿐이다.
 *
 * <p>응답: 200 이면 Apple 이 전달 완료로 보고, 그 밖이면 간격을 두고 다시 보낸다. Apple 에 물을 수 없을 때만 503 으로 재시도를 받는다.
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/subscriptions/app-store/notifications")
public class AppStoreNotificationController {

    private final AppStoreSubscriptionService service;
    private final byte[] notificationToken;

    public AppStoreNotificationController(AppStoreSubscriptionService service,
                                          @Value("${apple.app-store.notification-token:}") String notificationToken) {
        this.service = service;
        this.notificationToken = notificationToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestParam(name = "token", required = false) String token,
                                        @RequestBody(required = false) AppStoreNotificationRequest request) {
        if (notificationToken.length == 0 || token == null
                || !MessageDigest.isEqual(notificationToken, token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.signedPayload() == null || request.signedPayload().isBlank()) {
            return ResponseEntity.ok().build();  // 다시 보내도 읽을 수 없다: 받은 것으로 끝낸다
        }
        try {
            service.onNotification(request.signedPayload());
        } catch (AppStoreUnavailableException e) {
            log.warn("App Store notification deferred, Apple unavailable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok().build();
    }
}
