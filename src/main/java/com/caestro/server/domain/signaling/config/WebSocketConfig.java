package com.caestro.server.domain.signaling.config;

import com.caestro.server.domain.signaling.controller.SignalingWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    // 인바운드 텍스트 메시지 한도. 톰캣 기본 8KB는 ICE 후보가 많이 내장된 실측 SDP(10KB+)를
    // 1009로 끊는다(#97 실증). 버퍼가 커넥션마다 이 크기로 선할당되므로 필요 최소로 잡는다.
    private static final int MAX_TEXT_MESSAGE_BUFFER_BYTES = 16 * 1024;

    private final SignalingWebSocketHandler signalingWebSocketHandler;
    private final WebSocketAuthInterceptor authInterceptor;
    private final WebSocketDrainInterceptor drainInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(signalingWebSocketHandler, "/signaling")
                .addInterceptors(drainInterceptor, authInterceptor) // 드레인 거절(503)을 인증보다 앞에

                .setAllowedOrigins("*"); // 운영 환경에서는 실제 도메인으로 제한 필요
    }

    /**
     * WS 컨테이너 인바운드 한도 설정 (#97).
     * 무한 버퍼가 아니라 "의도한 경계로 명시"가 목적 — 한도 초과 메시지는 여전히 1009로 거부된다.
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BUFFER_BYTES);
        return container;
    }
}
