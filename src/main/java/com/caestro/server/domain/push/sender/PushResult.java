package com.caestro.server.domain.push.sender;

/** 토큰 하나에 보낸 결과. 지표 {@code push.sent{result}} 의 태그 값이기도 하다. */
public enum PushResult {
    /** FCM 이 받아 줬다(기기 도착은 FCM 몫). */
    SENT,
    /** FCM 이 "이 토큰은 더 이상 없다"고 답했다 — 토큰을 끈다. 앱 삭제·재설치가 대부분. */
    UNREGISTERED,
    /** FCM 장애·설정 오류·서킷 열림. 이번 알림은 잃는다(계약서: 잃어도 되는 알림만 1단계에 둔다). */
    FAILED,
    /** push.enabled=false — 로컬·CI. 보내지 않고 로그만. */
    DISABLED
}
