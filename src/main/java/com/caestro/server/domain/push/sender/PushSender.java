package com.caestro.server.domain.push.sender;

/**
 * 기기 하나에 알림 하나를 보내는 부품. 운영은 FCM({@link FcmPushSender}), 로컬·CI 는 보내지 않는 {@link NoopPushSender}.
 * 예외를 던지지 않고 {@link PushResult} 로 답한다 — 푸시는 최선 노력이고, 호출한 쪽 일(업로드 등)을 실패시키면 안 된다.
 */
public interface PushSender {

    PushResult send(String token, PushMessage message);

    /** 보낼 준비가 됐는가(키·프로젝트 설정). */
    boolean configured();
}
