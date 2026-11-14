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
                .build());
        // 발송 요청 수(#271)는 여기서 센다 — 행이 **커밋된** 수 = 요청 수. 사건이 롤백되면 세지 않고, 중복 건너뜀도 세지 않는다
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    metrics.requested(message.type(), 1);
                }
            });
        } else {
            metrics.requested(message.type(), 1);
        }
        return true;
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
