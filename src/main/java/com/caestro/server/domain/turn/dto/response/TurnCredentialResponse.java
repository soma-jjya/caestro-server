package com.caestro.server.domain.turn.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * WebRTC 연결에 사용할 iceServers 설정 응답.
 * 클라이언트가 RTCPeerConnection에 그대로 사용할 수 있는 형태이며, TURN 항목은 임시 자격증명을 포함한다.
 *
 * @param iceServers STUN/TURN 서버 목록
 * @param ttl        자격증명 유효 시간(초). 클라이언트는 만료 전 재요청한다.
 */
public record TurnCredentialResponse(
        List<IceServer> iceServers,
        long ttl
) {

    /**
     * 단일 ICE 서버 항목.
     * STUN은 자격증명이 없어 username/credential이 null이며, 직렬화 시 생략된다.
     *
     * @param urls       접속 URL 목록 (예: turn:host:3478?transport=udp)
     * @param username   TURN 임시 사용자명 ({만료시각}:{userId}), STUN이면 null
     * @param credential TURN 임시 자격증명 (base64 HMAC), STUN이면 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IceServer(
            List<String> urls,
            String username,
            String credential
    ) {
    }
}
