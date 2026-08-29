package com.caestro.server.domain.signaling.config;

import com.caestro.server.domain.signaling.service.SignalingDrainLifecycle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 드레인 중 신규 WS 핸드셰이크를 503으로 거절한다 (#106).
 *
 * 업그레이드 "전"에 거절하는 이유: 소켓을 열어 준 뒤 1012로 닫으면 그 틈에 첫 메시지(JOIN)가 처리돼
 * 곧 닫힐 소켓으로 takeover가 일어나는 오염이 생긴다(after 1차 실측: 1.2초 동안 거절 697건·유령 takeover 다수).
 * 핸드셰이크 단계에서 막으면 소켓 자체가 만들어지지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketDrainInterceptor implements HandshakeInterceptor {

    private final SignalingDrainLifecycle drainLifecycle;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!drainLifecycle.isDraining()) {
            return true;
        }
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1"); // 클라: 잠시 후(backoff) 다른 인스턴스로
        log.info("WebSocket handshake refused during drain");
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
