package com.caestro.server.domain.support.listener;

import com.caestro.server.domain.notification.service.NotificationDispatcher;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.support.event.SupportRepliedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 운영자 답변 → 알림함에 남기고 푸시 {@code support_reply} (#263). 커밋 뒤(AFTER_COMMIT), 요청 스레드 밖(@Async) —
 * 네 칸 완성 알림(#206)과 같은 길. 푸시 토큰이 없어도 알림함 행은 남아 앱을 열면 보인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportReplyNotifier {

    private final NotificationDispatcher dispatcher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReplied(SupportRepliedEvent event) {
        try {
            int sent = dispatcher.notify(event.userId(), PushMessage.supportReply(event.ticketId(), event.title()));
            log.info("Support reply push: ticket={} user={} sent={}", event.ticketId(), event.userId(), sent);
        } catch (RuntimeException e) {
            log.warn("Support reply push failed (ticket={}, user={}, cause={}: {})",
                    event.ticketId(), event.userId(), e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
