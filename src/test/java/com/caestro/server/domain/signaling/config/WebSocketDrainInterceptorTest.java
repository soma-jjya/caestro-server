package com.caestro.server.domain.signaling.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.signaling.service.SignalingDrainLifecycle;
import java.util.HashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

/** 드레인 중 핸드셰이크 거절(#106) — 소켓이 만들어지기 전에 503으로 막는다. */
@ExtendWith(MockitoExtension.class)
class WebSocketDrainInterceptorTest {

    @Mock private SignalingDrainLifecycle drainLifecycle;
    @Mock private ServerHttpRequest request;
    @Mock private ServerHttpResponse response;
    @Mock private WebSocketHandler wsHandler;

    @InjectMocks
    private WebSocketDrainInterceptor interceptor;

    @Test
    @DisplayName("드레인 중이면 503 + Retry-After로 핸드셰이크를 거절한다")
    void beforeHandshake_duringDrain_refusesWith503() {
        given(drainLifecycle.isDraining()).willReturn(true);
        HttpHeaders headers = new HttpHeaders();
        given(response.getHeaders()).willReturn(headers);

        boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertThat(allowed).isFalse();
        verify(response).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    @DisplayName("평상시엔 핸드셰이크를 통과시킨다")
    void beforeHandshake_normal_allows() {
        given(drainLifecycle.isDraining()).willReturn(false);

        boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertThat(allowed).isTrue();
        verify(response, never()).setStatusCode(any());
    }
}
