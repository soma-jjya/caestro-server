package com.caestro.server.domain.notification.service;

import java.time.LocalDateTime;

/**
 * {@link NotificationOutboxService#enqueue} 의 선택 사항 (#272).
 *
 * @param dedupKey   같은 알림을 두 번 넣지 않는 키. 필수 — 호출자가 "무엇이 같은 알림인지"를 정한다(예 fill_completed:{세션}:{사용자})
 * @param notBefore  이 시각 전에는 보내지 않음(null = 바로). 묶음 알림(#274)이 쓴다
 * @param expiresAt  이 시각이 지나면 보내지 않고 만료 처리(null = 시한 없음). 만료 안내처럼 때를 놓치면 의미 없는 알림
 * @param collapseKey 묶음 키(#274). 지금은 null
 */
public record EnqueueOptions(String dedupKey, LocalDateTime notBefore, LocalDateTime expiresAt, String collapseKey) {

    public static EnqueueOptions dedup(String dedupKey) {
        return new EnqueueOptions(dedupKey, null, null, null);
    }

    public EnqueueOptions expiring(LocalDateTime expiresAt) {
        return new EnqueueOptions(dedupKey, notBefore, expiresAt, collapseKey);
    }
}
