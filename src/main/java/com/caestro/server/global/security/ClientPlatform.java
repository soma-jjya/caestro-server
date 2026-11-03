package com.caestro.server.global.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 요청이 어느 앱에서 왔는지(User-Agent): 안드로이드 앱은 OkHttp 기본값 {@code okhttp/…},
 * iOS 앱은 URLSession 기본값 {@code peakpic/… CFNetwork/… Darwin/…}, 웹 페이지(축제 참여·웹 라이트)는 브라우저 {@code Mozilla/…}.
 * 어느 것도 아니면 null — 기록을 덮어쓰지 않는다. 운영 화면의 기기 칸에 쓴다 (contracts/studio-ops.md).
 */
public final class ClientPlatform {

    public static final String ANDROID = "ANDROID";
    public static final String IOS = "IOS";
    public static final String WEB = "WEB";

    private ClientPlatform() {
    }

    public static String fromUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return null;
        String ua = userAgent.toLowerCase();
        if (ua.contains("okhttp")) return ANDROID;
        // 앱의 URLSession 은 CFNetwork·Darwin 을 붙인다. 아이폰 사파리는 붙이지 않으므로 아래 웹으로 간다
        if (ua.contains("cfnetwork") || ua.contains("darwin")) return IOS;
        if (ua.startsWith("mozilla/")) return WEB;
        return null;
    }

    /** 지금 처리 중인 HTTP 요청의 플랫폼. 요청 밖(배치 등)에서는 null. */
    public static String current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        HttpServletRequest request = attributes.getRequest();
        return fromUserAgent(request.getHeader("User-Agent"));
    }
}
