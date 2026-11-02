package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.service.PushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 알림 하나를 "알림함에 먼저 남기고(#211) → 푸시(#206)" 순서로 내보내는 한 길. 네 칸 완성 리스너와 만료 안내 배치가 같이 쓴다.
 * 트랜잭션 안에서 부르지 않는다 — 저장은 자기 트랜잭션으로 끝나고 FCM 은 밖에서 호출한다(#184 와 같은 원칙).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationService notificationService;
    private final PushService pushService;

    /**
     * @return FCM 이 받아 준 기기 수
     */
    public int notify(Long userId, PushMessage message) {
        return pushService.sendToUser(userId, recorded(userId, message));
    }

    /** 알림함 행을 만들고 그 id 를 실은 메시지. 저장이 막혀도 푸시는 간다(id 없이) — 알림함 때문에 알림을 잃지 않는다. */
    private PushMessage recorded(Long userId, PushMessage message) {
        try {
            return message.withNotificationId(notificationService.record(userId, message).getId());
        } catch (RuntimeException e) {
            log.warn("Notification not recorded; push goes without notificationId (user={}, type={}, cause={}: {})",
                    userId, message.type(), e.getClass().getSimpleName(), e.getMessage());
            return message;
        }
    }
}
