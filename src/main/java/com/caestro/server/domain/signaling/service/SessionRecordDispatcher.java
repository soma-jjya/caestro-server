package com.caestro.server.domain.signaling.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 세션 DB 기록을 실시간 경로에서 분리하는 순서 보장 디스패처 (#99).
 * 세션 상태의 진실의 원천은 Redis이고 DB는 영구 기록이므로, WS 스레드는 기록 완료를 기다리지 않는다.
 *
 * 순서 보장: 같은 세션의 기록(create→join→spec→end)은 세션코드 해시로 고정된 워커(단일 스레드)가
 * 제출 순서대로 처리한다 — Kafka가 파티션 키로 순서를 보장하는 것과 같은 원리.
 * 경계: 워커 수 ≤ Hikari 풀(10)이라 동시 DB 사용량이 구조적으로 제한되고,
 * 큐는 유한(포화 시 드롭+지표)이라 몰림이 메모리 폭탄으로 바뀌지 않는다.
 */
@Slf4j
@Component
public class SessionRecordDispatcher {

    private static final int DEFAULT_WORKER_COUNT = 4;
    private static final int DEFAULT_QUEUE_CAPACITY = 1000;

    private final SignalingMetrics metrics;
    private final ThreadPoolExecutor[] workers;

    @Autowired // 생성자가 둘(운영/테스트용)이라 스프링이 쓸 것을 명시한다
    public SessionRecordDispatcher(SignalingMetrics metrics) {
        this(metrics, DEFAULT_WORKER_COUNT, DEFAULT_QUEUE_CAPACITY);
    }

    // 테스트에서 워커·큐 크기를 줄여 경계 동작을 검증할 수 있도록 분리
    SessionRecordDispatcher(SignalingMetrics metrics, int workerCount, int queueCapacity) {
        this.metrics = metrics;
        this.workers = new ThreadPoolExecutor[workerCount];
        for (int i = 0; i < workerCount; i++) {
            String name = "session-record-" + i;
            workers[i] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    r -> {
                        Thread t = new Thread(r, name);
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }
        metrics.bindRecordQueueDepth(this::totalQueuedTasks);
    }

    /**
     * 세션 기록 작업을 담당 워커 큐에 넣고 즉시 반환한다.
     * 실패(예외)와 포화(드롭)는 격리해 시그널링 흐름에 영향을 주지 않고 지표로만 남긴다.
     *
     * @param sessionCode 순서 보장의 파티션 키 (같은 코드 → 같은 워커)
     * @param taskName    지표 라벨용 작업 이름 (create/join/spec/end)
     * @param task        DB 기록 작업
     */
    public void dispatch(String sessionCode, String taskName, Runnable task) {
        int idx = Math.floorMod(sessionCode == null ? 0 : sessionCode.hashCode(), workers.length);
        try {
            workers[idx].execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    metrics.countRecordFailed(taskName);
                    log.error("Session record failed (isolated): task={}, session={}", taskName, sessionCode, e);
                }
            });
        } catch (RejectedExecutionException e) {
            metrics.countRecordDropped(taskName);
            log.warn("Session record dropped (queue full): task={}, session={}", taskName, sessionCode);
        }
    }

    private int totalQueuedTasks() {
        int sum = 0;
        for (ThreadPoolExecutor w : workers) {
            sum += w.getQueue().size();
        }
        return sum;
    }

    /** 종료(롤링 배포) 시 대기 중인 기록을 마저 반영해 유실을 최소화한다. */
    @PreDestroy
    void shutdown() {
        for (ThreadPoolExecutor w : workers) {
            w.shutdown();
        }
        try {
            for (ThreadPoolExecutor w : workers) {
                if (!w.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Session record queue not fully drained on shutdown: {} remain", w.getQueue().size());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
