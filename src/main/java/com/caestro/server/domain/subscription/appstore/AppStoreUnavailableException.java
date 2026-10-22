package com.caestro.server.domain.subscription.appstore;

/**
 * App Store Server API 에 물을 수 없다(키 미설정·네트워크·Apple 5xx). 확인 요청은 503, 알림은 재전송을 받는다.
 */
public class AppStoreUnavailableException extends RuntimeException {

    public AppStoreUnavailableException(String message) {
        super(message);
    }

    public AppStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
