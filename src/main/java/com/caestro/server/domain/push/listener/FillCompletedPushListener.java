package com.caestro.server.domain.push.listener;

import com.caestro.server.domain.fill.event.FillCompletedEvent;
import com.caestro.server.domain.notification.service.NotificationDispatcher;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 네 칸 완성 → 알림함에 남기고 주인과 칸을 채운 참여자에게 푸시 (#206, #211). 커밋 뒤(AFTER_COMMIT), 요청 스레드 밖(@Async)에서 돈다 —
 * 업로드 응답이 FCM 을 기다리지 않는다. 서버가 그 사이 재시작되면 이 알림은 잃는다. 1단계는 잃어도 되는 알림만 두기로 했고,
 * 큐(Redis Streams)로 가는 계기는 contracts/push.md.
 *
 * <p>참여자는 보낼 토큰이 있는 사람만 — 웹 참여자는 게스트 계정이라 볼 수 없는 알림함 행만 쌓인다. 한 사람이 실패해도 나머지는 받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FillCompletedPushListener {

    private final NotificationDispatcher dispatcher;
    private final PushTokenRepository tokenRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(FillCompletedEvent event) {
        int sent = dispatcher.notify(event.ownerId(), PushMessage.fillCompleted(event.code()));
        int participants = 0;
        int participantsSent = 0;
        for (Long participantId : event.participantIds()) {
            try {
                if (!tokenRepository.existsByUserIdAndDisabledAtIsNull(participantId)) {
                    continue;
                }
                participants++;
                participantsSent += dispatcher.notify(participantId, PushMessage.fillCompletedForParticipant(event.code()));
            } catch (RuntimeException e) {
                log.warn("Fill completed participant push failed (session={}, user={}, cause={}: {})",
                        event.sessionId(), participantId, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.info("Fill completed push: session={} owner={} sent={} participants={} participantsSent={}",
                event.sessionId(), event.ownerId(), sent, participants, participantsSent);
    }
}
