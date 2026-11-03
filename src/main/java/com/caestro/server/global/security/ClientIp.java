package com.caestro.server.global.security;

import jakarta.servlet.http.HttpServletRequest;

/** 요청을 보낸 클라이언트 IP. ALB 뒤에서는 X-Forwarded-For 의 첫 값이 실제 클라이언트다(직접 연결이면 remoteAddr). */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
    }
}
