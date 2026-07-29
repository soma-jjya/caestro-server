package com.caestro.server.domain.signaling.controller;

import com.caestro.server.domain.signaling.dto.request.SignalingRequest;
import com.caestro.server.domain.signaling.dto.response.SignalingResponse;
import com.caestro.server.domain.signaling.service.SignalingService;
import com.caestro.server.domain.signaling.service.WebSocketSessionManager;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignalingWebSocketHandler extends TextWebSocketHandler {

    private final SignalingService signalingService;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Client connected: {}", session.getId());
        sessionManager.addSession(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            SignalingRequest msg = objectMapper.readValue(message.getPayload(), SignalingRequest.class);
            log.info("Received message: type={}, sessionCode={}", msg.type(), msg.sessionCode());

            switch (msg.type()) {
                case "CREATE_SESSION" -> signalingService.createSession(session, msg);
                case "JOIN_SESSION" -> signalingService.joinSession(session, msg);
                case "DEVICE_SPEC" -> signalingService.handleDeviceSpec(session, msg);
                case "OFFER", "ANSWER", "ICE_CANDIDATE" -> signalingService.relay(session, msg);
                case "SWAP_ROLE" -> signalingService.swapRoles(session, msg);
                case "END_SESSION" -> signalingService.endSession(session, msg);
                default -> log.warn("Unknown message type: {}", msg.type());
            }
        } catch (Exception e) {
            log.error("Failed to handle message: {}", message.getPayload(), e);
            SignalingResponse errorResponse = SignalingResponse.builder()
                    .type("ERROR")
                    .message(ErrorCode.INVALID_SIGNALING_MESSAGE.getMessage())
                    .build();
            sessionManager.sendMessage(session.getId(), errorResponse);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Client disconnected: {} (status: {})", session.getId(), status);
        sessionManager.removeSession(session);

        signalingService.handleDisconnect(session);
    }
}
