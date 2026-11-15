package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.notification.entity.NotificationOutbox;
import com.caestro.server.domain.notification.entity.OutboxStatus;
import com.caestro.server.domain.notification.repository.NotificationOutboxRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 알림 아웃박스의 입구 (#272). 보내는 쪽은 이 한 함수만 부른다. 호출한 트랜잭션에 **합류**해 행을 넣으므로
 * 사건이 롤백되면 알림 요청도 같이 사라지고, 커밋되면 반드시 남는다. 발송은 {@link OutboxRelay} 몫.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationOutboxService {

    private final NotificationOutboxRepository repository;
    private final NotificationRequestMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 대기 중인 묶음 행의 dedup_key 꼬리. 집거나 취소하면 {@code :open#id} 로 바뀐다. */
    static final String OPEN_SUFFIX = ":open";

    /**
     * 알림 요청 한 건을 남긴다. 같은 {@code dedupKey} 가 이미 있으면 넣지 않는다(두 번 보내지 않음).
     *
     * @return 새로 넣었으면 true, 중복이라 건너뛰었으면 false
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean enqueue(Long userId, PushMessage message, EnqueueOptions options) {
        if (repository.existsByDedupKey(options.dedupKey())) {
            log.debug("Outbox skip duplicate: dedupKey={}", options.dedupKey());
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        repository.save(NotificationOutbox.builder()
                .userId(userId)
                .type(message.type())
                .title(message.title())
                .body(message.body())
                .data(encode(message.data()))
                .dedupKey(options.dedupKey())
                .collapseKey(options.collapseKey())
                .notBefore(options.notBefore() == null ? now : options.notBefore())
                .expiresAt(options.expiresAt())
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .mergedCount(1)
                .build());
        // 발송 요청 수(#271)는 여기서 센다 — 행이 **커밋된** 수 = 요청 수. 사건이 롤백되면 세지 않고, 중복 건너뜀도 세지 않는다
        afterCommit(() -> metrics.requested(message.type(), 1));
        return true;
    }

    /**
     * 묶음 알림 한 건(#274). 같은 [collapseKey] 의 행이 대기 중이면 새로 넣지 않고 합쳐 보낼 시각만 늦춘다:
     * 마지막 사건 + [quiet] 동안 조용하면 보내고, 처음 넣은 때 + [window] 를 넘겨 미루지는 않는다.
     * 직전 묶음을 보낸 지 [window] 가 안 됐으면 그때까지 기다린다 — 한 묶음 키로는 [window] 에 한 통.
     * 문구는 보내는 순간 {@link OutboxMessageRenderer} 가 다시 만든다. [message] 는 렌더러가 없을 때의 기본 문구.
     *
     * @return 새 행이면 true, 대기 중인 행에 합쳤으면 false
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean enqueueCollapsed(Long userId, PushMessage message, String collapseKey, LocalDateTime now, Duration quiet, Duration window) {
        // 1. 보낼 시각: 조용한 시간 뒤, 단 직전 발송 + window 보다 이르지 않게
        LocalDateTime notBefore = now.plus(quiet);
        LocalDateTime lastSent = repository.lastSentAt(collapseKey);
        if (lastSent != null && lastSent.plus(window).isAfter(notBefore)) {
            notBefore = lastSent.plus(window);
        }
        // 2. 넣거나 합치기 — 한 문장이라 동시에 와도 한 행
        int changed = repository.upsertCollapsed(userId, message.type(), message.title(), message.body(), encode(message.data()),
                collapseKey + OPEN_SUFFIX, collapseKey, notBefore, now, window.toSeconds());
        boolean inserted = changed == 1;
        // 3. 지표: 새 행은 요청 1, 합침은 요청이 아니라 합친 수
        afterCommit(inserted ? () -> metrics.requested(message.type(), 1) : () -> metrics.collapsed(message.type()));
        return inserted;
    }

    /**
     * 대기 중인 묶음 행을 취소한다(#274). 이미 집혀 큐에 들어간 행은 여기서 못 막고, 보내는 순간 렌더러가 다시 보고 취소한다.
     *
     * @return 취소한 행 수
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int cancelCollapsed(String type, String collapseKey) {
        int cancelled = repository.cancelPending(collapseKey, LocalDateTime.now(clock));
        if (cancelled > 0) afterCommit(() -> metrics.cancelled(type, cancelled));
        return cancelled;
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private String encode(Map<String, String> data) {
        try {
            String json = objectMapper.writeValueAsString(data == null ? Map.of() : data);
            return json.length() <= NotificationOutbox.DATA_MAX ? json : "{}";
        } catch (JsonProcessingException e) {
            log.warn("Outbox data not serialised; stored empty: {}", e.toString());
            return "{}";
        }
    }
}
