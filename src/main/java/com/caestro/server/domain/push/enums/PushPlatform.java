package com.caestro.server.domain.push.enums;

/** 기기 토큰을 발급한 플랫폼. 둘 다 FCM 으로 보낸다(iOS 는 FCM 이 APNs 로 넘긴다). */
public enum PushPlatform {
    ANDROID, IOS
}
