package com.caestro.server.domain.subscription.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.subscription.dto.request.PubSubPushRequest;
import com.caestro.server.domain.subscription.googleplay.GooglePlayUnavailableException;
import com.caestro.server.domain.subscription.service.GooglePlaySubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class GooglePlayNotificationControllerTest {

    private static final String SECRET = "push-secret";

    @Mock
    private GooglePlaySubscriptionService service;

    private GooglePlayNotificationController controller;

    @BeforeEach
    void setUp() {
        controller = new GooglePlayNotificationController(service, new ObjectMapper(), SECRET, "soma.project.caestro");
    }

    private static PubSubPushRequest push(String json) {
        String data = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return new PubSubPushRequest(new PubSubPushRequest.Message(data, "1", null), "projects/p/subscriptions/s");
    }

    private static final String RENEWED = """
            {"version":"1.0","packageName":"soma.project.caestro","eventTimeMillis":"1",
             "subscriptionNotification":{"version":"1.0","notificationType":2,"purchaseToken":"tok","subscriptionId":"peakpic_pro"}}
            """;

    @Test
    @DisplayName("토큰이 틀리거나 설정되지 않으면 403, 처리하지 않는다")
    void rejectsWrongToken() {
        assertThat(controller.receive("wrong", push(RENEWED)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.receive(null, push(RENEWED)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        GooglePlayNotificationController unset =
                new GooglePlayNotificationController(service, new ObjectMapper(), "", "soma.project.caestro");
        assertThat(unset.receive("", push(RENEWED)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).onSubscriptionNotification(anyString());
    }

    @Test
    @DisplayName("구독 알림은 토큰으로 다시 확인한다 (204)")
    void subscriptionNotification() {
        assertThat(controller.receive(SECRET, push(RENEWED)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).onSubscriptionNotification("tok");
    }

    @Test
    @DisplayName("구독 환불(voided, productType 1)은 끝내고, 일회성 상품 환불은 무시한다")
    void voided() {
        controller.receive(SECRET, push("""
                {"packageName":"soma.project.caestro","voidedPurchaseNotification":{"purchaseToken":"v1","orderId":"GPA.1","productType":1,"refundType":1}}
                """));
        controller.receive(SECRET, push("""
                {"packageName":"soma.project.caestro","voidedPurchaseNotification":{"purchaseToken":"v2","orderId":"GPA.2","productType":2,"refundType":1}}
                """));
        verify(service).onVoidedPurchase("v1");
        verify(service, never()).onVoidedPurchase("v2");
    }

    @Test
    @DisplayName("다른 패키지·테스트 알림·읽을 수 없는 메시지는 받은 것으로 끝낸다")
    void ignoresOthers() {
        assertThat(controller.receive(SECRET, push(RENEWED.replace("soma.project.caestro", "other.app")))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.receive(SECRET, push("{\"packageName\":\"soma.project.caestro\",\"testNotification\":{\"version\":\"1.0\"}}"))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.receive(SECRET, new PubSubPushRequest(new PubSubPushRequest.Message("%%%", "1", null), "s"))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service, never()).onSubscriptionNotification(anyString());
    }

    @Test
    @DisplayName("Google 에 물을 수 없으면 503 으로 Pub/Sub 재전송을 받는다")
    void retriesWhenGoogleUnavailable() {
        willThrow(new GooglePlayUnavailableException("down")).given(service).onSubscriptionNotification("tok");
        assertThat(controller.receive(SECRET, push(RENEWED)).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
