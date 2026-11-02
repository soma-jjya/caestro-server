package com.caestro.server.domain.push.listener;

import com.caestro.server.domain.fill.event.FillCompletedEvent;
import com.caestro.server.domain.notification.service.NotificationService;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.service.PushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 네 칸 완성 → 알림함에 먼저 남기고(#211) → 주인에게 푸시 (#206). 커밋 뒤(AFTER_COMMIT), 요청 스레드 밖(@Async)에서 돈다 — 업로드 응답이 FCM 을 기다리지 않는다.
 * 서버가 그 사이 재시작되면 이 알림은 잃는다. 1단계는 잃어도 되는 알림만 두기로 했고, 큐(Redis Streams)로 가는 계기는 contracts/push.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FillCompletedPushListener {

    private final NotificationService notificationService;
    private final PushService pushService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(FillCompletedEvent event) {
        PushMessage message = recorded(event.ownerId(), PushMessage.fillCompleted(event.code()));
        int sent = pushService.sendToUser(event.ownerId(), message);
        log.info("Fill completed push: session={} owner={} sent={}", event.sessionId(), event.ownerId(), sent);
    }

    /** 알림함 행을 만들고 그 id 를 실은 메시지. 저장이 막혀도 푸시는 간다(id 없이) — 알림함 때문에 알림을 잃지 않는다. */
    private PushMessage recorded(Long userId, PushMessage message) {
        try {
            return message.withNotificationId(notificationService.record(userId, message).getId());
        } catch (RuntimeException e) {
            log.warn("Notification not recorded; push goes without notificationId (owner={}, cause={}: {})",
                    userId, e.getClass().getSimpleName(), e.getMessage());
            return message;
        }
    }
}
