package com.caestro.server.domain.push.dto.response;

/** 테스트 발송 결과: 내 활성 토큰 수와 그중 FCM 이 받아 준 수. 둘이 다르면 FCM 응답을 서버 로그에서 본다. */
public record PushTestResponse(int tokens, int sent) {
}
