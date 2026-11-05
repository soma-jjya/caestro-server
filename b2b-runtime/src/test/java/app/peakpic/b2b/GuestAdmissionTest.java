package app.peakpic.b2b;

import app.peakpic.b2b.auth.*;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuestAdmissionTest {
    private final GuestAdmissionProperties quota = new GuestAdmissionProperties(30, 60, List.of("10.80.0.0/24", "10.80.1.0/24"));
    private MockHttpServletRequest request(String peer, String forwarded) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(peer);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }
    @Test void onlyTrustedProxyHopsMayIdentifyTheClient() {
        var resolver = new GuestClientIp(quota);
        assertThat(resolver.resolve(request("203.0.113.10", "198.51.100.1"))).isEqualTo("203.0.113.10");
        assertThat(resolver.resolve(request("10.80.0.20", "198.51.100.1, 203.0.113.10"))).isEqualTo("203.0.113.10");
        assertThat(resolver.resolve(request("10.80.0.20", "203.0.113.10, 10.80.1.20"))).isEqualTo("203.0.113.10");
        assertThat(resolver.resolve(request("10.80.0.20", "2001:db8::1")))
                .isEqualTo(resolver.resolve(request("10.80.0.20", "2001:0db8:0:0:0:0:0:1")));
        assertThatThrownBy(() -> resolver.resolve(request("10.80.0.20", "localhost"))).isInstanceOf(RuntimeFailure.class);
        var duplicated = request("10.80.0.20", "203.0.113.1"); duplicated.addHeader("X-Forwarded-For", "203.0.113.2");
        assertThatThrownBy(() -> resolver.resolve(duplicated)).isInstanceOf(RuntimeFailure.class);
    }
    @Test void twoAppInstancesShareOneIpBudgetAndDoNotCountSpoofedIpsSeparately() {
        var shared = new ConcurrentHashMap<String, AtomicInteger>();
        var limiter = mock(RedisRateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenAnswer(call ->
                shared.computeIfAbsent(call.getArgument(0), key -> new AtomicInteger()).incrementAndGet() <= (int) call.getArgument(1));
        var metrics = new SimpleMeterRegistry();
        var p = RuntimeIsolationTest.properties("bp_test1234", "abcdef123456");
        var first = new GuestAdmission(new GuestClientIp(quota), quota, p, new EventWindow(p, RuntimeIsolationTest.NOW), limiter, metrics);
        var second = new GuestAdmission(new GuestClientIp(quota), quota, p, new EventWindow(p, RuntimeIsolationTest.NOW), limiter, metrics);
        for (int i = 0; i < 30; i++) (i % 2 == 0 ? first : second).acquire(request("10.80.0.20", "198.51.100." + i + ", 203.0.113.10"));
        assertThatThrownBy(() -> second.acquire(request("10.80.1.20", "203.0.113.10")))
                .isInstanceOfSatisfying(GuestAdmission.Rejected.class, failure -> {
                    assertThat(failure.scope()).isEqualTo("guest-ip"); assertThat(failure.retryAfter()).isEqualTo(60);
                });
        assertThatCode(() -> second.acquire(request("10.80.1.20", "203.0.113.11"))).doesNotThrowAnyException();
        assertThat(shared.keySet()).noneMatch(key -> key.contains("203.0.113"));
        assertThat(metrics.counter("b2b.guest.rate_limited", "scope", "ip").count()).isEqualTo(1);
    }
    @Test void runtimeBudgetIsReportedSeparatelyFromIpBudget() {
        var limiter = mock(RedisRateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenAnswer(call -> !((String) call.getArgument(0)).endsWith(":total"));
        var p = RuntimeIsolationTest.properties("bp_test1234", "abcdef123456");
        var admission = new GuestAdmission(new GuestClientIp(quota), quota, p,
                new EventWindow(p, RuntimeIsolationTest.NOW), limiter, new SimpleMeterRegistry());
        assertThatThrownBy(() -> admission.acquire(request("203.0.113.10", null)))
                .isInstanceOfSatisfying(GuestAdmission.Rejected.class, failure -> assertThat(failure.scope()).isEqualTo("guest-runtime"));
    }
}
