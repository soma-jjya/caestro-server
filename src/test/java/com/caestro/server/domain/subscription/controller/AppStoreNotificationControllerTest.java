package com.caestro.server.domain.subscription.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.subscription.appstore.AppStoreUnavailableException;
import com.caestro.server.domain.subscription.dto.request.AppStoreNotificationRequest;
import com.caestro.server.domain.subscription.service.AppStoreSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AppStoreNotificationControllerTest {

    private static final String SECRET = "notify-secret";
    private static final AppStoreNotificationRequest BODY = new AppStoreNotificationRequest("a.b.c");

    @Mock
    private AppStoreSubscriptionService service;

    private AppStoreNotificationController controller;

    @BeforeEach
    void setUp() {
        controller = new AppStoreNotificationController(service, SECRET);
    }

    @Test
    @DisplayName("토큰이 틀리거나 설정되지 않으면 403, 처리하지 않는다")
    void rejectsWrongToken() {
        assertThat(controller.receive("wrong", BODY).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.receive(null, BODY).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        AppStoreNotificationController unset = new AppStoreNotificationController(service, "");
        assertThat(unset.receive("", BODY).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).onNotification(anyString());
    }

    @Test
    @DisplayName("알림은 서비스가 다시 확인하고 200, 빈 본문은 받은 것으로 끝낸다")
    void handsOver() {
        assertThat(controller.receive(SECRET, BODY).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).onNotification("a.b.c");
        assertThat(controller.receive(SECRET, new AppStoreNotificationRequest(" ")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(controller.receive(SECRET, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Apple 에 물을 수 없으면 503 으로 다시 받는다")
    void defersWhenAppleIsUnavailable() {
        willThrow(new AppStoreUnavailableException("down")).given(service).onNotification(anyString());
        assertThat(controller.receive(SECRET, BODY).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
