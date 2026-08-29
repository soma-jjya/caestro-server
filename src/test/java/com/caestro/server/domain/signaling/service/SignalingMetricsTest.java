package com.caestro.server.domain.signaling.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.TimeUnit;

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

    @Test
    @DisplayName("메시지 처리시간이 type 라벨 Timer로 기록되고, count가 수신량 카운터를 겸한다")
    void recordHandled_byType() {
        metrics.recordHandled("ICE_CANDIDATE", 1_000_000L); // 1ms
        metrics.recordHandled("ICE_CANDIDATE", 3_000_000L); // 3ms
        metrics.recordHandled("PING", 500_000L);

        assertThat(registry.get("ws.message.handle").tag("type", "ICE_CANDIDATE").timer().count()).isEqualTo(2);
        assertThat(registry.get("ws.message.handle").tag("type", "ICE_CANDIDATE").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(4.0);
        assertThat(registry.get("ws.message.handle").tag("type", "PING").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("허용 목록 밖 타입과 null은 other로 정규화되어 라벨 폭증을 막는다")
    void recordHandled_unknownTypeNormalized() {
        metrics.recordHandled("HACK_TYPE_12345", 1_000_000L);
        metrics.recordHandled(null, 1_000_000L);

        assertThat(registry.get("ws.message.handle").tag("type", "other").timer().count()).isEqualTo(2);
    }

    @ParameterizedTest(name = "code {0} → label {1}")
    @CsvSource({
            "1000, 1000",   // 클라 정상 종료
            "1006, 1006",   // 비정상(close 프레임 없음)
            "1012, 1012",   // 서버 재시작(드레인)
            "1015, 1015",   // 표준 범위 상한
            "1016, other",  // 표준 범위 밖
            "3500, 3xxx",   // 라이브러리/프레임워크 예약
            "4999, 4xxx",   // 애플리케이션 커스텀
            "999,  other",
    })
    @DisplayName("종료 코드는 표준/3xxx/4xxx/other 라벨로 정규화된다 (#105)")
    void countClose_normalizesLabel(int code, String label) {
        metrics.countClose(code);

        assertThat(registry.get("ws.close").tag("code", label).counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 종료 코드 라벨은 하나의 시계열에 누적된다")
    void countClose_accumulatesPerLabel() {
        metrics.countClose(4001);
        metrics.countClose(4500);

        assertThat(registry.get("ws.close").tag("code", "4xxx").counter().count()).isEqualTo(2);
    }
}
