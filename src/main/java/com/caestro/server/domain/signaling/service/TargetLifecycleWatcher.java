package com.caestro.server.domain.signaling.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ALB 등록 해제를 인스턴스 안에서 감지해 SIGTERM보다 먼저 드레인한다 (#111).
 *
 * 운영 실측(2026-08-30): 롤링 배포에서 ALB는 등록 해제 지연(30s)이 끝나는 순간 기존 WS 연결을 일괄 종료하고,
 * 인스턴스 종료(SIGTERM)는 그 뒤다. SIGTERM에 걸린 드레인은 이미 죽은 소켓에 실행돼 클라이언트는 1006만 봤다.
 * 정돈된 종료는 등록 해제 지연 창 "안에서" 해야 하며, 그 시작 신호가 IMDS의 target-lifecycle-state다
 * (ASG가 인스턴스를 종료 상태로 보내는 순간 InService → Terminated로 바뀐다. AWS가 "종료 전 코드 실행" 용도로 제공).
 *
 * IMDS는 인스턴스 로컬 서비스라 API 한도·비용·IAM이 없다. 2초 폴링 비용은 무시할 수준.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "signaling.drain.imds", name = "enabled", havingValue = "true")
public class TargetLifecycleWatcher {

    static final String TOKEN_PATH = "/latest/api/token";
    static final String STATE_PATH = "/latest/meta-data/autoscaling/target-lifecycle-state";
    static final String TOKEN_HEADER = "X-aws-ec2-metadata-token";
    static final String TOKEN_TTL_HEADER = "X-aws-ec2-metadata-token-ttl-seconds";
    // 서비스에서 빠지는 목표 상태 — 어느 쪽이든 ALB 등록 해제가 따라온다
    static final Set<String> LEAVING_STATES = Set.of("Terminated", "Detached", "Standby");

    private final SignalingDrainLifecycle drainLifecycle;
    private final RestClient client;
    private volatile String token;
    private final AtomicBoolean triggered = new AtomicBoolean(false);
    private volatile boolean unavailableLogged = false;

    // 생성자가 둘이면 Spring이 어느 쪽을 쓸지 몰라 기본 생성자를 찾다 실패한다(2026-09-01 운영 기동 실패의 원인).
    // @Autowired로 "이걸 써라"를 명시한다 — SessionRecordDispatcher(#99)와 동일한 함정.
    @Autowired
    public TargetLifecycleWatcher(SignalingDrainLifecycle drainLifecycle,
                                  @Value("${signaling.drain.imds.base-url:http://169.254.169.254}") String baseUrl) {
        this(drainLifecycle, buildClient(baseUrl));
    }

    TargetLifecycleWatcher(SignalingDrainLifecycle drainLifecycle, RestClient client) {
        this.drainLifecycle = drainLifecycle;
        this.client = client;
    }

    private static RestClient buildClient(String baseUrl) {
        // IMDS는 로컬이라 1초면 충분 — 느리면 스케줄 스레드를 잡지 않고 이번 회차를 버린다
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(1));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 주기 폴링. 서비스에서 빠지는 상태를 처음 본 순간 드레인을 한 번 호출하고 이후엔 아무것도 하지 않는다. */
    @Scheduled(fixedDelayString = "${signaling.drain.imds.poll-ms:2000}")
    public void poll() {
        if (triggered.get()) {
            return;
        }
        try {
            String state = fetchState();
            if (state != null && LEAVING_STATES.contains(state) && triggered.compareAndSet(false, true)) {
                log.info("Target lifecycle state={} → draining now, before ALB deregistration completes", state);
                drainLifecycle.drain("imds:" + state);
            }
        } catch (Exception e) {
            logUnavailableOnce("IMDS unreachable: " + e.getMessage());
        }
    }

    /** IMDSv2: 토큰(PUT)을 붙여 상태를 읽는다. 401이면 토큰을 재발급해 한 번 더 시도, 404면 ASG 밖(null). */
    private String fetchState() {
        if (token == null) {
            token = fetchToken();
        }
        try {
            return readState();
        } catch (HttpClientErrorException.Unauthorized e) {
            token = fetchToken();
            return readState();
        } catch (HttpClientErrorException.NotFound e) {
            logUnavailableOnce("target-lifecycle-state not found (instance not in an Auto Scaling group?)");
            return null;
        }
    }

    private String readState() {
        String body = client.get().uri(STATE_PATH).header(TOKEN_HEADER, token).retrieve().body(String.class);
        return body == null ? null : body.trim();
    }

    private String fetchToken() {
        String issued = client.put().uri(TOKEN_PATH).header(TOKEN_TTL_HEADER, "21600").retrieve().body(String.class);
        return issued == null ? null : issued.trim();
    }

    private void logUnavailableOnce(String reason) {
        if (!unavailableLogged) {
            unavailableLogged = true;
            log.warn("Target lifecycle watch inactive — {} (will keep polling quietly)", reason);
        }
    }
}
