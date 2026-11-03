package com.caestro.server.global.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 실제 폰 한 대를 가리키는 값(헤더 X-Device-Key)의 SHA-256 hex. 안드로이드 앱은 ANDROID_ID, iOS 앱은 키체인에 둔
 * 무작위 값(재설치·로그아웃에도 그대로)을 보낸다. 게스트 기기 id(deviceId)는 iOS 가 로그아웃마다 새로 만들고 탈퇴하면
 * 지워지므로, 한 폰에서 생긴 계정들을 운영 화면에서 한 줄로 묶으려면 이 값이 따로 필요하다 (contracts/studio-ops.md).
 * 원래 값은 저장하지 않고 해시만 둔다. 헤더가 없으면 null — 기록을 덮어쓰지 않는다.
 */
public final class ClientDevice {

    public static final String HEADER = "X-Device-Key";
    private static final int MAX_LENGTH = 200;

    private ClientDevice() {
    }

    public static String hash(String rawKey) {
        if (rawKey == null) return null;
        String key = rawKey.strip();
        if (key.isEmpty() || key.length() > MAX_LENGTH) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 지금 처리 중인 HTTP 요청의 기기 키 해시. 요청 밖이거나 헤더가 없으면 null. */
    public static String current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        HttpServletRequest request = attributes.getRequest();
        return hash(request.getHeader(HEADER));
    }
}
