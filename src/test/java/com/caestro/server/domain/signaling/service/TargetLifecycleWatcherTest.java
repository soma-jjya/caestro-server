package com.caestro.server.domain.signaling.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

/**
 * IMDS 감지 드레인(#111) — JDK 내장 HttpServer로 IMDSv2를 흉내 내 검증한다:
 * Terminated 감지 시 드레인 1회 / 이후 중복 없음 / InService면 무반응 / 404·401·서버 부재에 조용히 견딤.
 */
@ExtendWith(MockitoExtension.class)
class TargetLifecycleWatcherTest {

    @Mock
    private SignalingDrainLifecycle drainLifecycle;

    private HttpServer imds;
    private final AtomicReference<String> state = new AtomicReference<>("InService");   // null이면 404
    private final AtomicReference<String> validToken = new AtomicReference<>("tok-1");  // PUT이 발급하고 GET이 검사
    private TargetLifecycleWatcher watcher;

    @BeforeEach
    void startFakeImds() throws IOException {
        imds = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        imds.createContext(TargetLifecycleWatcher.TOKEN_PATH, ex -> {
            if (!"PUT".equals(ex.getRequestMethod())) { respond(ex, 405, ""); return; }
            respond(ex, 200, validToken.get());
        });
        imds.createContext(TargetLifecycleWatcher.STATE_PATH, ex -> {
            String presented = ex.getRequestHeaders().getFirst(TargetLifecycleWatcher.TOKEN_HEADER);
            if (!validToken.get().equals(presented)) { respond(ex, 401, ""); return; }
            String s = state.get();
            if (s == null) { respond(ex, 404, ""); return; }
            respond(ex, 200, s);
        });
        imds.start();
        RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:" + imds.getAddress().getPort()).build();
        watcher = new TargetLifecycleWatcher(drainLifecycle, client);
    }

    @AfterEach
    void stopFakeImds() {
        imds.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    @Test
    @DisplayName("InService 동안은 아무것도 하지 않는다")
    void inService_noDrain() {
        watcher.poll();
        watcher.poll();

        verify(drainLifecycle, never()).drain(anyString());
    }

    @Test
    @DisplayName("Terminated로 바뀐 첫 폴링에서 드레인을 정확히 한 번 호출하고, 이후 폴링은 무시한다")
    void terminated_drainsOnce() {
        watcher.poll();                 // InService
        state.set("Terminated");
        watcher.poll();                 // 감지 → 드레인
        watcher.poll();                 // 중복 호출 없어야 함

        verify(drainLifecycle, times(1)).drain("imds:Terminated");
    }

    @Test
    @DisplayName("토큰이 만료돼 401이 오면 재발급 후 같은 회차에 다시 읽는다")
    void expiredToken_refreshesAndReads() {
        watcher.poll();                 // tok-1 발급
        validToken.set("tok-2");        // 서버 측에서 토큰 회전 → 기존 토큰은 401
        state.set("Terminated");
        watcher.poll();

        verify(drainLifecycle, times(1)).drain("imds:Terminated");
    }

    @Test
    @DisplayName("404(ASG 밖 인스턴스)면 드레인 없이 조용히 넘어간다")
    void notFound_noDrainNoException() {
        state.set(null);

        watcher.poll();
        watcher.poll();

        verify(drainLifecycle, never()).drain(anyString());
    }

    @Test
    @DisplayName("IMDS에 연결할 수 없어도 예외를 밖으로 던지지 않는다 (스케줄러 생존)")
    void unreachable_noException() {
        imds.stop(0);

        watcher.poll();

        verify(drainLifecycle, never()).drain(anyString());
    }
}
