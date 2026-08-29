package com.caestro.server.domain.signaling.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * WS 시그널링 운영 지표. Micrometer 계측을 한 곳에 모아
 * 비즈니스 코드에는 의도(countXxx)만 노출한다. /actuator/prometheus 로 수집된다.
 */
@Component
public class SignalingMetrics {

    private final MeterRegistry registry;
    private final Counter relayLocal;
    private final Counter relayPubsub;
    private final Counter relayNoReceiver;
    private final Counter sessionCreated;
    private final Counter sessionEnded;
    private final Counter reaperClosed;
    // gauge는 상태 객체를 약참조로 잡으므로, GC되지 않도록 이 빈(영구 생존)이 supplier를 강참조로 보관한다
    private volatile Supplier<Number> activeConnections = () -> 0;
    // 타입별 처리시간 Timer 캐시 — 핫패스에서 매번 레지스트리를 조회(Meter.Id 생성)하는 비용을 피한다
    private final Map<String, Timer> handleTimers = new ConcurrentHashMap<>();
    // type 라벨 허용 목록 — 임의 문자열이 라벨로 유입돼 시계열이 폭증하는 것 방지 (목록 밖은 other로 정규화)
    private static final Set<String> HANDLE_TYPE_LABELS = Set.of(
            "CREATE_SESSION", "JOIN_SESSION", "DEVICE_SPEC", "OFFER", "ANSWER",
            "ICE_CANDIDATE", "SWAP_ROLE", "END_SESSION", "PING", "error");

    public SignalingMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 중계 경로: local(같은 인스턴스 직접 전달) vs pubsub(Redis 경유 크로스 인스턴스)
        // → 두 값의 비율이 "브로드캐스트 → 타깃 발행" 전환 판단의 근거가 된다
        this.relayLocal = Counter.builder("ws.relay.sent")
                .tag("route", "local")
                .description("시그널링 메시지 전달 수 (로컬 직접)")
                .register(registry);
        this.relayPubsub = Counter.builder("ws.relay.sent")
                .tag("route", "pubsub")
                .description("시그널링 메시지 전달 수 (Redis Pub/Sub 크로스 인스턴스)")
                .register(registry);
        // Pub/Sub은 fire-and-forget이라 수신자 0이면 메시지가 조용히 사라진다 → 유실 감지용
        this.relayNoReceiver = Counter.builder("ws.relay.no_receiver")
                .description("Pub/Sub 발행 시 수신 인스턴스가 0이었던 횟수 (메시지 유실 신호)")
                .register(registry);
        // 주의: 이름을 ...created로 끝내면 OpenMetrics 예약 접미사(_created)와 충돌해 잘린다 → event 태그로 구분
        this.sessionCreated = Counter.builder("ws.session")
                .tag("event", "created")
                .description("시그널링 세션 수명주기 이벤트")
                .register(registry);
        this.sessionEnded = Counter.builder("ws.session")
                .tag("event", "ended")
                .description("시그널링 세션 수명주기 이벤트")
                .register(registry);
        this.reaperClosed = Counter.builder("ws.reaper.closed")
                .description("리퍼가 정리한 유휴 소켓 수 (급증 시 클라 PING 미동작 신호)")
                .register(registry);
    }

    // gauge 강참조 보관 (activeConnections와 동일한 이유)
    private volatile Supplier<Number> recordQueueDepth = () -> 0;

    /** 세션 DB 기록 대기 큐 길이 gauge 등록 (#99). 포화 접근을 관측하기 위한 지표. */
    public void bindRecordQueueDepth(Supplier<Number> depth) {
        this.recordQueueDepth = depth;
        Gauge.builder("ws.record.queue.depth", this, m -> m.recordQueueDepth.get().doubleValue())
                .description("세션 DB 기록 대기 큐 길이 (전 워커 합)")
                .register(registry);
    }

    /** DB 기록 작업 실패 횟수 (예외는 격리되고 지표만 남는다) */
    public void countRecordFailed(String task) {
        registry.counter("ws.record.failed", "task", task).increment();
    }

    /** DB 기록 큐 포화로 버려진 작업 수 (0이 아니면 워커·큐 재조정 신호) */
    public void countRecordDropped(String task) {
        registry.counter("ws.record.dropped", "task", task).increment();
    }

    /** 활성 WS 연결 수 gauge 등록. (호출 시점의 값을 읽어가는 방식이라 supplier로 받는다) */
    public void bindActiveConnections(Supplier<Number> activeCount) {
        this.activeConnections = activeCount;
        Gauge.builder("ws.connections.active", this, m -> m.activeConnections.get().doubleValue())
                .description("이 인스턴스의 활성 WebSocket 연결 수")
                .register(registry);
    }

    /**
     * WS 종료 코드 분포 (#105). 배포·장애 시 소켓이 "어떤 코드로" 죽는지의 증거.
     * 3xxx/4xxx 커스텀 코드는 범위로 묶어 클라이언트가 임의 코드로 시계열을 늘리지 못하게 한다.
     */
    public void countClose(int code) {
        String label;
        if (code >= 1000 && code <= 1015) label = String.valueOf(code);   // RFC 6455 + IANA 표준 코드
        else if (code >= 3000 && code <= 3999) label = "3xxx";
        else if (code >= 4000 && code <= 4999) label = "4xxx";
        else label = "other";
        registry.counter("ws.close", "code", label).increment();
    }

    /** 드레인(#106)이 정돈 종료(1012)로 닫은 소켓 수. 배포 1회당 영향 세션 규모의 서버 측 기록. */
    public void countDrainClosed(int closed) {
        registry.counter("ws.drain.closed").increment(closed);
    }

    public void countRelay(boolean local) {
        (local ? relayLocal : relayPubsub).increment();
    }

    public void countRelayNoReceiver() {
        relayNoReceiver.increment();
    }

    /** JOIN 판정 결과(Lua 반환값)를 라벨로 기록: claimed/occupied/takeover_owner/... */
    public void countJoin(String result) {
        registry.counter("ws.join", "result", result).increment();
    }

    /**
     * 메시지 1건의 처리시간(수신 파싱→처리 완료)을 타입 라벨의 Timer로 기록한다.
     * Timer의 count가 타입별 수신량 카운터를 겸한다. 파싱 실패로 타입 불명이면 "error"로 들어온다.
     */
    public void recordHandled(String type, long elapsedNanos) {
        String label = type != null && HANDLE_TYPE_LABELS.contains(type) ? type : "other";
        handleTimers.computeIfAbsent(label, t -> Timer.builder("ws.message.handle")
                        .tag("type", t)
                        .description("시그널링 메시지 처리시간 (수신 파싱→처리 완료)")
                        // 버킷 히스토그램 발행: Prometheus에서 인스턴스 횡단 분위수 집계(histogram_quantile)용
                        .publishPercentileHistogram()
                        .minimumExpectedValue(Duration.ofNanos(100_000)) // 100µs
                        .maximumExpectedValue(Duration.ofSeconds(10))
                        .register(registry))
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void countSessionCreated() {
        sessionCreated.increment();
    }

    public void countSessionEnded() {
        sessionEnded.increment();
    }

    public void countReaperClosed(int closed) {
        reaperClosed.increment(closed);
    }
}
