package com.caestro.server.domain.subscription.googleplay;

/** Google Play 확인을 지금 할 수 없다(키 미설정·네트워크·Google 5xx). 클라이언트에는 503, 실시간 알림은 재시도. */
public class GooglePlayUnavailableException extends RuntimeException {

    public GooglePlayUnavailableException(String message) {
        super(message);
    }

    public GooglePlayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
