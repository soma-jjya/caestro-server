package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.fill.event.FillCompletedEvent;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.support.event.SupportRepliedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 도메인 사건 → 아웃박스 행 (#272). **동기** {@code @EventListener} 라 사건을 발행한 트랜잭션 안에서 돈다 —
 * 전의 {@code @Async @TransactionalEventListener}(커밋 뒤 메모리)가 바로 유실 지점이었다.
 * 네 칸 완성은 주인 + 칸을 채운 참여자(기기 토큰이 있는 사람만 — 웹 참여자는 볼 수 없는 알림함 행만 쌓이므로 전과 같이 뺀다).
 */
@Component
@RequiredArgsConstructor
public class OutboxEnqueuer {

    private final NotificationOutboxService outbox;
    private final PushTokenRepository tokenRepository;

    @EventListener
    public void onFillCompleted(FillCompletedEvent event) {
        outbox.enqueue(event.ownerId(), PushMessage.fillCompleted(event.code()),
                EnqueueOptions.dedup(dedupKey(PushMessage.TYPE_FILL_COMPLETED, event.sessionId(), event.ownerId())));
        for (Long participantId : event.participantIds()) {
            if (!tokenRepository.existsByUserIdAndDisabledAtIsNull(participantId)) continue;
            outbox.enqueue(participantId, PushMessage.fillCompletedForParticipant(event.code()),
                    EnqueueOptions.dedup(dedupKey(PushMessage.TYPE_FILL_COMPLETED, event.sessionId(), participantId)));
        }
    }

    @EventListener
    public void onSupportReplied(SupportRepliedEvent event) {
        outbox.enqueue(event.userId(), PushMessage.supportReply(event.ticketId(), event.title()),
                EnqueueOptions.dedup(PushMessage.TYPE_SUPPORT_REPLY + ":" + event.ticketId() + ":" + event.messageNo()));
    }

    static String dedupKey(String type, Long sessionId, Long userId) {
        return type + ":" + sessionId + ":" + userId;
    }
}
