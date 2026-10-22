package com.caestro.server.domain.subscription.appstore;

import java.util.Optional;

/**
 * App Store Server API 중 구독 확인에 쓰는 호출. 테스트에서는 이 인터페이스를 흉내 낸다.
 */
public interface AppStoreServerApi {

    /**
     * 거래 번호(구독 한 줄의 어느 거래든)로 그 구독의 지금 상태를 Apple 에 묻는다.
     *
     * @return Apple 이 모르는 거래(잘못된·다른 앱의 번호)이면 빈 값
     * @throws AppStoreUnavailableException 키가 없거나 Apple 이 일시적으로 응답하지 않을 때
     */
    Optional<AppStoreSubscription> getSubscription(String transactionId);

    /** 인앱 결제 키(issuer id · key id · .p8)가 설정돼 확인을 할 수 있는가. */
    boolean configured();
}
