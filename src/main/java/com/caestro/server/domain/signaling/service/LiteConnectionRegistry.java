package com.caestro.server.domain.signaling.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 라이트 모드 토큰당 활성 WebSocket 연결을 1개로 제한하기 위한 레지스트리.
 * 토큰 → 현재 활성 소켓 ID 매핑을 관리하며, "재연결"과 "동시 접속"을 구분한다.
 * - 기존 연결이 이미 끊어진(죽은) 상태에서 같은 토큰으로 들어오면 → 재연결로 간주(허용)
 * - 기존 연결이 살아있는 상태에서 같은 토큰으로 또 들어오면 → 동시 접속으로 간주(거부)
 * 연결 생존 여부는 기존 WebSocketSessionManager(WebSocketSession.isOpen())를 그대로 활용한다.
 */
@Component
@RequiredArgsConstructor
public class LiteConnectionRegistry {

    private final WebSocketSessionManager sessionManager;
    private final Map<String, String> activeConnections = new ConcurrentHashMap<>();

    /**
     * 해당 토큰으로 현재 살아있는 활성 연결이 있는지 확인한다.
     * 매핑된 소켓이 이미 닫혔으면 스테일 항목으로 보고 정리한 뒤 false를 반환한다(재연결 허용).
     *
     * @param token 라이트 모드 참여 토큰
     * @return 살아있는 동시 연결이 존재하면 true
     */
    public boolean hasLiveConnection(String token) {
        String socketId = activeConnections.get(token);
        if (socketId == null) {
            return false;
        }
        if (sessionManager.isConnected(socketId)) {
            return true;
        }
        // 기존 연결이 죽어있으면 스테일 항목 정리 후 재연결 허용
        activeConnections.remove(token, socketId);
        return false;
    }

    /**
     * 토큰의 활성 연결로 소켓을 등록한다.
     *
     * @param token    라이트 모드 참여 토큰
     * @param socketId 활성 소켓 ID
     */
    public void register(String token, String socketId) {
        activeConnections.put(token, socketId);
    }

    /**
     * 토큰의 활성 연결 등록을 해제한다 (현재 매핑이 해당 소켓일 때만).
     * 연결 종료 시 호출되어 다음 접속이 신규 접속처럼 처리되게 한다.
     *
     * @param token    라이트 모드 참여 토큰
     * @param socketId 해제할 소켓 ID
     */
    public void release(String token, String socketId) {
        activeConnections.remove(token, socketId);
    }
}
