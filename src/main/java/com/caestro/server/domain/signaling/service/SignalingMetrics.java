package com.caestro.server.domain.signaling.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

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

    /** 활성 WS 연결 수 gauge 등록. (호출 시점의 값을 읽어가는 방식이라 supplier로 받는다) */
    public void bindActiveConnections(Supplier<Number> activeCount) {
        this.activeConnections = activeCount;
        Gauge.builder("ws.connections.active", this, m -> m.activeConnections.get().doubleValue())
                .description("이 인스턴스의 활성 WebSocket 연결 수")
                .register(registry);
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
