package com.caestro.server.domain.signaling.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis 채널로 발행된 크로스 인스턴스 중계 메시지를 수신하는 구독자.
 * 모든 인스턴스가 동일 채널을 구독하며, 발행된 메시지의 대상 소켓이 "이 인스턴스"에 있을 때만 전달한다.
 * 대상 소켓이 없으면(다른 인스턴스 소관) 조용히 무시한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalingPubSubSubscriber implements MessageListener {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    /**
     * 채널로 발행된 중계 메시지를 수신하여 대상 소켓이 로컬에 있으면 전달한다.
     *
     * @param message 발행된 메시지 (본문은 {@link SignalingRelayMessage}의 JSON)
     * @param pattern 매칭된 패턴 (사용 안 함)
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            SignalingRelayMessage relay = objectMapper.readValue(body, SignalingRelayMessage.class);

            // 대상 소켓이 이 인스턴스에 있을 때만 전달 (아니면 다른 인스턴스가 처리하므로 무시)
            boolean localDeliver = sessionManager.isConnected(relay.socketId());
            log.debug("[PUBSUB] received: socketId={}, localDeliver={}", relay.socketId(), localDeliver);
            if (localDeliver) {
                sessionManager.sendRawMessage(relay.socketId(), relay.payload());
            }
        } catch (Exception e) {
            // 구독 처리 실패가 다른 메시지 수신을 막지 않도록 예외를 격리
            log.error("Failed to handle relay message from Redis", e);
        }
    }
}
