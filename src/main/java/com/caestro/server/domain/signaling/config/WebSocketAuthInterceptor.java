package com.caestro.server.domain.signaling.config;

import com.caestro.server.domain.signaling.service.LiteConnectionRegistry;
import com.caestro.server.domain.signaling.service.LiteTokenService;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final JwtProvider jwtProvider;
    private final LiteTokenService liteTokenService;
    private final LiteConnectionRegistry liteConnectionRegistry;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }

        String token = servletRequest.getServletRequest().getParameter("token");
        if (token == null || token.isBlank()) {
            log.warn("WebSocket authentication failed: missing token");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // 1. JWT 인증 (디렉터 또는 로그인한 촬영자)
        if (jwtProvider.validateToken(token)) {
            Long userId = jwtProvider.getUserId(token);
            attributes.put("userId", userId);
            attributes.put("authType", "JWT");
            return true;
        }

        // 2. 라이트 모드 토큰 인증 (로그인 없이 QR로 참여한 촬영자)
        String sessionCode = liteTokenService.resolveSessionCode(token);
        if (sessionCode == null) {
            // 존재하지 않거나 세션 종료로 무효화된 토큰
            log.warn("WebSocket authentication failed: {}", ErrorCode.LITE_TOKEN_INVALID.getMessage());
            response.setStatusCode(HttpStatus.valueOf(ErrorCode.LITE_TOKEN_INVALID.getStatus()));
            return false;
        }

        // 3. 동시 접속 제어: 이미 살아있는 연결이 있으면 새 연결 거부 (정책 a)
        if (liteConnectionRegistry.hasLiveConnection(token)) {
            log.warn("WebSocket authentication failed: {} (token already has a live connection)",
                    ErrorCode.LITE_CONCURRENT_CONNECTION.getMessage());
            response.setStatusCode(HttpStatus.valueOf(ErrorCode.LITE_CONCURRENT_CONNECTION.getStatus()));
            return false;
        }

        attributes.put("liteToken", token);
        attributes.put("sessionCode", sessionCode);
        attributes.put("authType", "LITE");
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
