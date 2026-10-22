package com.caestro.server.domain.subscription.controller;

import com.caestro.server.domain.subscription.dto.request.PubSubPushRequest;
import com.caestro.server.domain.subscription.googleplay.GooglePlayUnavailableException;
import com.caestro.server.domain.subscription.service.GooglePlaySubscriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
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
 * Google Play 실시간 개발자 알림(RTDN) 수신. Cloud Pub/Sub 푸시 구독이 이 주소로 보낸다.
 *
 * <p>인증: 푸시 엔드포인트 URL 에 넣은 ?token= 값(google.play.rtdn-token)과 비교한다. 비어 있으면 모두 403.
 * 알림 내용은 믿지 않고 구매 토큰만 꺼내 Google 에 다시 묻는다. 그래서 위조된 알림이 와도 할 수 있는 일은 재확인뿐이다.
 *
 * <p>응답: 2xx 면 Pub/Sub 이 전달 완료로 보고, 그 밖이면 백오프로 다시 보낸다. Google 에 물을 수 없을 때만 503 으로 재시도를 받는다.
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/subscriptions/google-play/notifications")
public class GooglePlayNotificationController {

    // voidedPurchaseNotification.productType: 1 = 구독, 2 = 일회성 상품
    private static final int VOIDED_PRODUCT_TYPE_SUBSCRIPTION = 1;

    private final GooglePlaySubscriptionService service;
    private final ObjectMapper objectMapper;
    private final byte[] pushToken;
    private final String packageName;

    public GooglePlayNotificationController(GooglePlaySubscriptionService service,
                                            ObjectMapper objectMapper,
                                            @Value("${google.play.rtdn-token:}") String pushToken,
                                            @Value("${google.play.package-name:soma.project.caestro}") String packageName) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.pushToken = pushToken.getBytes(StandardCharsets.UTF_8);
        this.packageName = packageName;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestParam(name = "token", required = false) String token,
                                        @RequestBody(required = false) PubSubPushRequest request) {
        if (pushToken.length == 0 || token == null
                || !MessageDigest.isEqual(pushToken, token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        JsonNode notification = decode(request);
        if (notification == null) {
            return ResponseEntity.noContent().build();  // 다시 보내도 읽을 수 없다: 받은 것으로 끝낸다
        }
        if (!packageName.equals(notification.path("packageName").asText())) {
            log.warn("RTDN for another package: {}", notification.path("packageName").asText());
            return ResponseEntity.noContent().build();
        }
        try {
            JsonNode subscription = notification.path("subscriptionNotification");
            JsonNode voided = notification.path("voidedPurchaseNotification");
            if (subscription.hasNonNull("purchaseToken")) {
                log.info("RTDN subscription notificationType={}", subscription.path("notificationType").asInt());
                service.onSubscriptionNotification(subscription.path("purchaseToken").asText());
            } else if (voided.hasNonNull("purchaseToken")
                    && voided.path("productType").asInt() == VOIDED_PRODUCT_TYPE_SUBSCRIPTION) {
                service.onVoidedPurchase(voided.path("purchaseToken").asText());
            } else if (notification.has("testNotification")) {
                log.info("RTDN test notification received");
            }
        } catch (GooglePlayUnavailableException e) {
            log.warn("RTDN deferred, Google Play unavailable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.noContent().build();
    }

    private JsonNode decode(PubSubPushRequest request) {
        if (request == null || request.message() == null || request.message().data() == null) {
            return null;
        }
        try {
            byte[] json = Base64.getDecoder().decode(request.message().data());
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("RTDN message unreadable: {}", e.getMessage());
            return null;
        }
    }
}
