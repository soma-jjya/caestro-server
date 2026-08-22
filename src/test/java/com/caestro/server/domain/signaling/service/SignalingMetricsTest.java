package com.caestro.server.domain.signaling.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 지표 이름·태그·값이 의도대로 기록되는지 실제 레지스트리로 검증한다. */
class SignalingMetricsTest {

    private SimpleMeterRegistry registry;
    private SignalingMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SignalingMetrics(registry);
    }

    @Test
    @DisplayName("중계 경로가 local/pubsub 태그로 구분 집계된다")
    void countRelay_byRoute() {
        metrics.countRelay(true);
        metrics.countRelay(true);
        metrics.countRelay(false);

        assertThat(registry.get("ws.relay.sent").tag("route", "local").counter().count()).isEqualTo(2);
        assertThat(registry.get("ws.relay.sent").tag("route", "pubsub").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("JOIN 판정값이 result 라벨로 집계된다")
    void countJoin_byResult() {
        metrics.countJoin("claimed");
        metrics.countJoin("occupied");
        metrics.countJoin("claimed");

        assertThat(registry.get("ws.join").tag("result", "claimed").counter().count()).isEqualTo(2);
        assertThat(registry.get("ws.join").tag("result", "occupied").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("수신자 0·리퍼 정리·세션 수명주기 카운터가 기록된다")
    void countOthers() {
        metrics.countRelayNoReceiver();
        metrics.countReaperClosed(3);
        metrics.countSessionCreated();
        metrics.countSessionEnded();

        assertThat(registry.get("ws.relay.no_receiver").counter().count()).isEqualTo(1);
        assertThat(registry.get("ws.reaper.closed").counter().count()).isEqualTo(3);
        assertThat(registry.get("ws.session").tag("event", "created").counter().count()).isEqualTo(1);
        assertThat(registry.get("ws.session").tag("event", "ended").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("활성 연결 gauge는 supplier의 현재 값을 읽는다")
    void activeConnectionsGauge() {
        int[] size = {0};
        metrics.bindActiveConnections(() -> size[0]);

        size[0] = 7;
        assertThat(registry.get("ws.connections.active").gauge().value()).isEqualTo(7);
    }
}
