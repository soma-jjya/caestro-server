package com.caestro.server.domain.subscription.googleplay;

import java.util.Optional;

/**
 * Google Play Developer API 중 구독 확인에 쓰는 두 호출. 테스트에서는 이 인터페이스를 흉내 낸다.
 */
public interface GooglePlayPublisher {

    /**
     * 구매 토큰의 현재 상태를 Google 에 묻는다.
     *
     * @return Google 이 모르는 토큰(잘못된·다른 앱의 토큰)이면 빈 값
     * @throws GooglePlayUnavailableException 자격 증명이 없거나 Google 이 일시적으로 응답하지 않을 때
     */
    Optional<GooglePlaySubscription> getSubscription(String purchaseToken);

    /**
     * 구독 구매를 승인한다. 3일 안에 승인되지 않은 구매는 Google 이 환불한다(앱도 승인하므로 이미 승인됐을 수 있다).
     */
    void acknowledge(String productId, String purchaseToken);

    /** 서비스 계정 키가 설정돼 확인을 할 수 있는가. */
    boolean configured();
}
