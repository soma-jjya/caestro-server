package com.caestro.server.domain.signaling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

/**
 * 하트비트가 끊긴(유휴 한계를 초과한) 소켓을 주기적으로 정리한다.
 * 네트워크 전환 등으로 정상 종료 신호 없이 죽은 "유령 소켓"을 능동적으로 닫아,
 * 슬롯 회수와 상태 정확도를 확보한다.
 *
 * 소켓을 닫으면 afterConnectionClosed → handleDisconnect가 실행되어
 * 기존 이탈 처리(참여자 재연결 대기 / 방장 유예)가 그대로 적용된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalingHeartbeatReaper {

    private final WebSocketSessionManager sessionManager;
    private final SignalingMetrics metrics;

    // 유휴 한계(ms): 이 시간 넘게 활동이 없으면 죽은 소켓으로 간주. 클라이언트 PING 주기(~25s)의 여유 배수로 설정.
    @Value("${signaling.heartbeat.max-idle-ms:90000}")
    private long maxIdleMillis;

    /**
     * 주기적으로 유휴 소켓을 찾아 닫는다. (기본 30초 간격)
     */
    @Scheduled(fixedDelayString = "${signaling.heartbeat.reaper-interval-ms:30000}")
    public void reapStaleSessions() {
        List<WebSocketSession> stale = sessionManager.findStaleSessions(maxIdleMillis);
        if (stale.isEmpty()) {
            return;
        }

        log.info("Reaping {} stale websocket session(s) (maxIdle={}ms)", stale.size(), maxIdleMillis);
        metrics.countReaperClosed(stale.size());
        for (WebSocketSession session : stale) {
            try {
                // 닫으면 afterConnectionClosed → handleDisconnect로 이탈 처리가 이어진다.
                session.close(CloseStatus.GOING_AWAY);
            } catch (Exception e) {
                log.warn("Failed to close stale session {}", session.getId(), e);
            }
        }
    }
}
