package com.caestro.server.domain.signaling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 상대 소켓에게 시그널링 메시지를 전달한다.
 * WebSocket 소켓은 그것을 수립한 인스턴스 메모리에만 존재하므로, 다른 인스턴스의 소켓에는 직접 write할 수 없다.
 * 따라서 "로컬 우선, 없으면 발행" 전략을 사용한다.
 * - 대상 소켓이 이 인스턴스에 있으면 직접 전달한다 (단일 인스턴스면 항상 이 경로 → 기존 동작·성능 불변).
 * - 없으면 Redis 채널로 발행하여, 소켓을 가진 다른 인스턴스가 구독해 대신 전달하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalingRelaySender {

    /** 크로스 인스턴스 중계용 Redis Pub/Sub 채널 (발행/구독이 공유) */
    public static final String CHANNEL = "signaling:relay";

    private final WebSocketSessionManager sessionManager;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 대상 소켓에게 메시지를 전달한다.
     *
     * @param socketId 전달 대상 소켓 ID
     * @param payload  클라이언트에게 보낼 메시지 객체
     */
    public void send(String socketId, Object payload) {
        if (socketId == null) return;

        // 1. 이 인스턴스가 대상 소켓을 가지고 있으면 직접 전달 (단일 인스턴스면 항상 여기)
        if (sessionManager.isConnected(socketId)) {
            sessionManager.sendMessage(socketId, payload);
            return;
        }

        // 2. 로컬에 없으면 Redis 채널로 발행 → 소켓을 가진 다른 인스턴스가 대신 전달
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String envelope = objectMapper.writeValueAsString(new SignalingRelayMessage(socketId, payloadJson));
            redisTemplate.convertAndSend(CHANNEL, envelope);
            log.debug("[PUBSUB] published (socket not local): socketId={}", socketId);
        } catch (JsonProcessingException e) {
            // 발행 실패가 시그널링 흐름을 막지 않도록 예외를 격리
            log.error("Failed to publish relay message: socketId={}", socketId, e);
        }
    }
}
