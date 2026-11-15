package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.fill.event.FillCompletedEvent;
import com.caestro.server.domain.fill.event.FillProgressEvent;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.support.event.SupportRepliedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 도메인 사건 → 아웃박스 행 (#272). **동기** {@code @EventListener} 라 사건을 발행한 트랜잭션 안에서 돈다 —
 * 전의 {@code @Async @TransactionalEventListener}(커밋 뒤 메모리)가 바로 유실 지점이었다.
 * 네 칸 완성은 주인 + 칸을 채운 참여자(기기 토큰이 있는 사람만 — 웹 참여자는 볼 수 없는 알림함 행만 쌓이므로 전과 같이 뺀다).
 * 친구의 칸 채움(#274)은 주인에게만, 세션별 한 행에 묶는다. 완성되면 대기 중인 그 행은 취소한다 — 주인은 "완성됐어요" 한 통만.
 */
@Component
public class OutboxEnqueuer {

    private final NotificationOutboxService outbox;
    private final PushTokenRepository tokenRepository;
    private final Duration progressQuiet;
    private final Duration progressWindow;

    public OutboxEnqueuer(NotificationOutboxService outbox, PushTokenRepository tokenRepository,
                          @Value("${notification.fill-progress.quiet-seconds:600}") long progressQuietSeconds,
                          @Value("${notification.fill-progress.window-seconds:3600}") long progressWindowSeconds) {
        this.outbox = outbox;
        this.tokenRepository = tokenRepository;
        this.progressQuiet = Duration.ofSeconds(progressQuietSeconds);
        this.progressWindow = Duration.ofSeconds(progressWindowSeconds);
    }

    @EventListener
    public void onFillCompleted(FillCompletedEvent event) {
        outbox.cancelCollapsed(PushMessage.TYPE_FILL_PROGRESS, progressKey(event.sessionId(), event.ownerId()));
        outbox.enqueue(event.ownerId(), PushMessage.fillCompleted(event.code()),
                EnqueueOptions.dedup(dedupKey(PushMessage.TYPE_FILL_COMPLETED, event.sessionId(), event.ownerId())));
        for (Long participantId : event.participantIds()) {
            if (!tokenRepository.existsByUserIdAndDisabledAtIsNull(participantId)) continue;
            outbox.enqueue(participantId, PushMessage.fillCompletedForParticipant(event.code()),
                    EnqueueOptions.dedup(dedupKey(PushMessage.TYPE_FILL_COMPLETED, event.sessionId(), participantId)));
        }
    }

    @EventListener
    public void onFillProgress(FillProgressEvent event) {
        outbox.enqueueCollapsed(event.ownerId(), PushMessage.fillProgressPlaceholder(event.code()),
                progressKey(event.sessionId(), event.ownerId()), event.at(), progressQuiet, progressWindow);
    }

    @EventListener
    public void onSupportReplied(SupportRepliedEvent event) {
        outbox.enqueue(event.userId(), PushMessage.supportReply(event.ticketId(), event.title()),
                EnqueueOptions.dedup(PushMessage.TYPE_SUPPORT_REPLY + ":" + event.ticketId() + ":" + event.messageNo()));
    }

    static String dedupKey(String type, Long sessionId, Long userId) {
        return type + ":" + sessionId + ":" + userId;
    }

    /** 친구 칸 채움 묶음의 키. 세션·받는 사람(주인)마다 하나. */
    public static String progressKey(Long sessionId, Long ownerId) {
        return dedupKey(PushMessage.TYPE_FILL_PROGRESS, sessionId, ownerId);
    }
}
