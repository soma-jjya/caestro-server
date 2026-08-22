package com.caestro.server.domain.signaling.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSessionManager {

    private static final int SEND_TIME_LIMIT = 10 * 1000; // 10초
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024; // 512KB

    private final ObjectMapper objectMapper;
    private final SignalingMetrics metrics;
    private final Map<String, WebSocketSession> socketMap = new ConcurrentHashMap<>();
    // 소켓별 마지막 활동 시각(ms). 하트비트/메시지 수신 시 갱신하여 유휴 소켓 판별에 사용한다.
    private final Map<String, Long> lastSeenMap = new ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    void bindMetrics() {
        metrics.bindActiveConnections(socketMap::size);
    }

    public void addSession(WebSocketSession session) {
        WebSocketSession concurrentSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT, SEND_BUFFER_SIZE_LIMIT
        );
        socketMap.put(session.getId(), concurrentSession);
        lastSeenMap.put(session.getId(), System.currentTimeMillis());
        log.info("WebSocket Session Add: {}", session.getId());
    }

    public void removeSession(WebSocketSession session) {
        socketMap.remove(session.getId());
        lastSeenMap.remove(session.getId());
        log.info("WebSocket Session Remove: {}", session.getId());
    }

    /**
     * 소켓의 마지막 활동 시각을 현재로 갱신한다. (메시지 수신·PING 시 호출)
     *
     * @param socketId 활동이 감지된 소켓 ID
     */
    public void updateLastSeen(String socketId) {
        lastSeenMap.computeIfPresent(socketId, (id, prev) -> System.currentTimeMillis());
    }

    /**
     * 지정한 유휴 한계(ms)를 초과해 활동이 없는(=죽었을 가능성이 큰) 소켓들을 반환한다.
     *
     * @param maxIdleMillis 유휴로 판단할 한계 시간(ms)
     * @return 유휴 한계를 초과한 소켓 목록
     */
    public List<WebSocketSession> findStaleSessions(long maxIdleMillis) {
        long now = System.currentTimeMillis();
        List<WebSocketSession> stale = new ArrayList<>();
        lastSeenMap.forEach((socketId, lastSeen) -> {
            if (now - lastSeen > maxIdleMillis) {
                WebSocketSession session = socketMap.get(socketId);
                if (session != null) {
                    stale.add(session);
                }
            }
        });
        return stale;
    }

    /**
     * 해당 소켓이 현재 연결되어 살아있는지(open) 확인한다.
     *
     * @param socketId 확인할 소켓 ID
     * @return 세션이 존재하고 열려 있으면 true
     */
    public boolean isConnected(String socketId) {
        if (socketId == null) return false;
        WebSocketSession session = socketMap.get(socketId);
        return session != null && session.isOpen();
    }

    public void sendMessage(String socketId, Object payload) {
        if (socketId == null) return;

        WebSocketSession target = socketMap.get(socketId);
        if (target != null && target.isOpen()) {
            try {
                target.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } catch (IOException e) {
                log.error("Failed to send message to session {}", socketId, e);
            }
        } else {
            log.warn("Target session {} not found or closed", socketId);
        }
    }

    /**
     * 이미 직렬화된 JSON 문자열을 소켓에 그대로 전달한다.
     * Redis Pub/Sub으로 넘어온 메시지는 발행 측에서 이미 JSON으로 직렬화되어 있으므로,
     * 재직렬화 없이 원문을 그대로 write한다.
     *
     * @param socketId 대상 소켓 ID
     * @param json     클라이언트에게 보낼 JSON 문자열
     */
    public void sendRawMessage(String socketId, String json) {
        if (socketId == null) return;

        WebSocketSession target = socketMap.get(socketId);
        if (target != null && target.isOpen()) {
            try {
                target.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.error("Failed to send raw message to session {}", socketId, e);
            }
        } else {
            log.warn("Target session {} not found or closed (raw)", socketId);
        }
    }
}
