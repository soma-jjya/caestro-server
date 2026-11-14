package com.caestro.server.domain.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 아웃박스 행 → Redis 스트림 (#273). 메시지에는 {@code outboxId} 만 싣는다 — 내용·개인정보는 DB 에 있고, 큐가 날아가도 DB 가 원본이다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.stream.enabled", havingValue = "true")
public class NotificationStreamPublisher {

    static final String FIELD_OUTBOX_ID = "outboxId";

    private final RedisTemplate<String, String> redisTemplate;
    private final OutboxSettings settings;

    public RecordId publish(Long outboxId) {
        return redisTemplate.opsForStream().add(StreamRecords.newRecord()
                .in(settings.streamKey())
                .ofMap(Map.of(FIELD_OUTBOX_ID, String.valueOf(outboxId))));
    }

    /** 오래된 항목 정리(대략 길이). 매 릴레이 바퀴 끝에 한 번. */
    public void trim() {
        redisTemplate.opsForStream().trim(settings.streamKey(), settings.maxlen(), true);
    }

    /** 보류함 스트림에 남긴다(사람이 보고 다시 보내거나 지운다). */
    public void publishDead(Long outboxId, String type, String error) {
        redisTemplate.opsForStream().add(StreamRecords.newRecord()
                .in(settings.deadKey())
                .ofMap(Map.of(FIELD_OUTBOX_ID, String.valueOf(outboxId), "type", type, "error", error == null ? "" : error)));
        redisTemplate.opsForStream().trim(settings.deadKey(), settings.maxlen(), true);
    }
}
