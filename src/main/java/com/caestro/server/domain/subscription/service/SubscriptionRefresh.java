package com.caestro.server.domain.subscription.service;

import com.caestro.server.domain.subscription.entity.Subscription;

/**
 * 스토어에 다시 물어 행에 반영한 결과 (#185). 실시간 알림은 지표 태그로, 대사 배치는 "몇 건이 실제로 바뀌었나"로 쓴다.
 */
public enum SubscriptionRefresh {

    /** 권한 기준(status · expiresAt · storeState · autoRenewing)이 바뀌었다 — 놓친 갱신·해지·환불을 따라잡은 것. */
    CHANGED,
    /** 스토어와 이미 같았다. */
    UNCHANGED,
    /** 반영할 행이 없다(아직 계정에 안 묶인 토큰·스토어가 모르는 토큰·다른 상품). */
    SKIPPED;

    /** 바뀜 판정용 스냅샷. 사용자가 체감하는 값만 모은다. */
    static String snapshot(Subscription s) {
        return s.getStatus() + "|" + s.getExpiresAt() + "|" + s.getStoreState() + "|" + s.getAutoRenewing();
    }
}
