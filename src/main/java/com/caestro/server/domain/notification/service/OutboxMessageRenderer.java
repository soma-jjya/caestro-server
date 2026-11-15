package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.push.sender.PushMessage;

import java.util.Optional;

/**
 * 보내는 순간 문구를 다시 만드는 알림 종류(#274 묶음 알림). 행에 적힌 숫자는 넣을 때의 것이라, 합쳐진 뒤의 상태를 도메인에서 다시 읽는다.
 * {@link OutboxDelivery} 가 [type] 이 같은 행에만 부른다.
 */
public interface OutboxMessageRenderer {

    String type();

    /**
     * @param stored 행에 저장된 기본 문구와 data
     * @return 보낼 문구. 비어 있으면 더 보낼 이유가 없다는 뜻(완성됨·세션 사라짐) — 행은 CANCELLED
     */
    Optional<PushMessage> render(PushMessage stored);
}
