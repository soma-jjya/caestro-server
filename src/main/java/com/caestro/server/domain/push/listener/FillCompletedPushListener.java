package com.caestro.server.domain.push.listener;

import com.caestro.server.domain.fill.event.FillCompletedEvent;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.service.PushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 네 칸 완성 → 주인에게 푸시 (#206). 커밋 뒤(AFTER_COMMIT), 요청 스레드 밖(@Async)에서 돈다 — 업로드 응답이 FCM 을 기다리지 않는다.
 * 서버가 그 사이 재시작되면 이 알림은 잃는다. 1단계는 잃어도 되는 알림만 두기로 했고, 큐(Redis Streams)로 가는 계기는 contracts/push.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FillCompletedPushListener {

    private final PushService pushService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(FillCompletedEvent event) {
        int sent = pushService.sendToUser(event.ownerId(), PushMessage.fillCompleted(event.code()));
        log.info("Fill completed push: session={} owner={} sent={}", event.sessionId(), event.ownerId(), sent);
    }
}
