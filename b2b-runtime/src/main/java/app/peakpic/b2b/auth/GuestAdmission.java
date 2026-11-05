package app.peakpic.b2b.auth;

import app.peakpic.b2b.*;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Admission runs before the guest service opens a database transaction. Both limits are shared through Redis. */
@Component
public class GuestAdmission {
    public static class Rejected extends RuntimeFailure {
        private final String scope;
        private final int retryAfter;
        public Rejected(String scope, int retryAfter) {
            super(429, "요청이 너무 잦습니다. " + retryAfter + "초 뒤에 다시 시도해 주세요.");
            this.scope = scope; this.retryAfter = retryAfter;
        }
        public String scope() { return scope; }
        public int retryAfter() { return retryAfter; }
    }
    private final GuestClientIp clientIp;
    private final GuestAdmissionProperties quota;
    private final RuntimeProperties runtime;
    private final EventWindow window;
    private final RedisRateLimiter limiter;
    private final MeterRegistry metrics;
    public GuestAdmission(GuestClientIp clientIp, GuestAdmissionProperties quota, RuntimeProperties runtime,
            EventWindow window, RedisRateLimiter limiter, MeterRegistry metrics) {
        this.clientIp = clientIp; this.quota = quota; this.runtime = runtime; this.window = window;
        this.limiter = limiter; this.metrics = metrics;
    }
    public void acquire(HttpServletRequest request) {
        window.requireReadable();
        String key = runtime.issuer() + ":guest:ip:" + hash(clientIp.resolve(request));
        if (!limiter.tryAcquire(key, quota.ipLimit(), Duration.ofSeconds(quota.windowSeconds()))) reject("ip", quota.windowSeconds());
        if (!limiter.tryAcquire(runtime.issuer() + ":guest:total", runtime.guestLimitPerMinute(), Duration.ofMinutes(1))) reject("runtime", 60);
        metrics.counter("b2b.guest.admitted").increment();
    }
    private void reject(String scope, int seconds) {
        metrics.counter("b2b.guest.rate_limited", "scope", scope).increment();
        throw new Rejected("guest-" + scope, seconds);
    }
    private static String hash(String ip) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
