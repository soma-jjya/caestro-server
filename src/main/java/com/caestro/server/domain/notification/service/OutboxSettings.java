package com.caestro.server.domain.notification.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 알림 아웃박스·스트림 설정 (#272, #273). 테스트가 짧은 값으로 바로 만들 수 있게 record 로 둔다.
 *
 * @param workerEnabled 이 인스턴스가 릴레이 주기·스트림 소비자를 돌리는가(#274 notifier 분리의 스위치. 테스트는 끈다)
 * @param streamEnabled 전달을 Redis Streams 로(true) / 릴레이가 직접 FCM 으로(false, #272 방식). 롤백은 이 한 줄
 * @param backoff       n 번째 실패 뒤 다시 보내기까지 기다리는 시간. 넘치면 마지막 값
 */
public record OutboxSettings(boolean workerEnabled, boolean streamEnabled, int batch, int maxAttempts, Duration lease,
                             List<Duration> backoff, String streamKey, String deadKey, String group, long maxlen,
                             Duration claimIdle, Duration relayedLease) {

    public Duration backoffAfter(int attempts) {
        if (attempts <= 0) return claimIdle;
        return backoff.get(Math.min(attempts - 1, backoff.size() - 1));
    }

    @Configuration
    static class Factory {
        @Bean
        OutboxSettings outboxSettings(
                @Value("${notification.worker.enabled:true}") boolean workerEnabled,
                @Value("${notification.stream.enabled:false}") boolean streamEnabled,
                @Value("${notification.outbox.batch:50}") int batch,
                @Value("${notification.outbox.max-attempts:5}") int maxAttempts,
                @Value("${notification.outbox.lease-seconds:60}") long leaseSeconds,
                @Value("${notification.outbox.backoff:60s,300s,1800s,7200s,21600s}") List<Duration> backoff,
                @Value("${notification.stream.key:notifications}") String streamKey,
                @Value("${notification.stream.dead-key:notifications.dead}") String deadKey,
                @Value("${notification.stream.group:notifier}") String group,
                @Value("${notification.stream.maxlen:10000}") long maxlen,
                @Value("${notification.stream.claim-idle-seconds:60}") long claimIdleSeconds,
                @Value("${notification.stream.relayed-lease-hours:12}") long relayedLeaseHours) {
            return new OutboxSettings(workerEnabled, streamEnabled, batch, maxAttempts, Duration.ofSeconds(leaseSeconds), backoff,
                    streamKey, deadKey, group, maxlen, Duration.ofSeconds(claimIdleSeconds), Duration.ofHours(relayedLeaseHours));
        }
    }
}
