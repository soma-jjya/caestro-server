package app.peakpic.b2b;

import app.peakpic.b2b.auth.GuestTokens;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Date;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeIsolationTest {
    static final Clock NOW = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);
    static RuntimeProperties properties(String project, String runtime) {
        return new RuntimeProperties("test", project, "yuja", runtime, "http://localhost:8080",
                NOW.instant().minusSeconds(60), NOW.instant().plusSeconds(3600), NOW.instant().plusSeconds(86400),
                "only-test-secret-012345678901234567890123456789", "only-test-admin-012345678901234567890123456789",
                "peakpic-b2b-test-only", "ap-northeast-2", 300);
    }
    @Test void accessTokensAreBoundToProjectRuntimeAndIssuerEvenWhenKeyIsReused() {
        var p = properties("bp_test1234", "abcdef123456");
        var token = new GuestTokens(p, NOW).issue(7);
        assertThat(new GuestTokens(p, NOW).verify(token)).isEqualTo(7);
        assertThatThrownBy(() -> new GuestTokens(properties("bp_other123", p.runtimeId()), NOW).verify(token)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new GuestTokens(properties(p.projectId(), "abcdef654321"), NOW).verify(token)).isInstanceOf(RuntimeException.class);
        String b2c = Jwts.builder().issuer("peakpic").subject("7").expiration(Date.from(NOW.instant().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(p.jwtSecret().getBytes(StandardCharsets.UTF_8))).compact();
        assertThatThrownBy(() -> new GuestTokens(p, NOW).verify(b2c)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new GuestTokens(p, Clock.offset(NOW, Duration.ofMinutes(16))).verify(token)).isInstanceOf(RuntimeException.class);
    }
    @Test void bucketPathsCannotCollideAcrossProjectsOrDatabaseRebuilds() {
        var first = properties("bp_test1234", "abcdef123456");
        var other = properties("bp_other123", first.runtimeId());
        var rebuilt = properties(first.projectId(), "abcdef654321");
        assertThat(first.prefix() + "/fills/1/").isNotEqualTo(other.prefix() + "/fills/1/")
                .isNotEqualTo(rebuilt.prefix() + "/fills/1/");
        assertThatThrownBy(() -> new RuntimeBinding(first).requireMatch(rebuilt)).isInstanceOf(IllegalStateException.class);
        assertThat(first.toString()).doesNotContain(first.jwtSecret(), first.adminKey());
    }
    @Test void eventEndStopsWritesButRetainsReadsUntilDeadline() {
        var p = properties("bp_test1234", "abcdef123456");
        var afterEnd = new EventWindow(p, Clock.fixed(p.endsAt(), ZoneOffset.UTC));
        assertThatThrownBy(afterEnd::requireWritable).isInstanceOf(RuntimeFailure.class);
        assertThatCode(afterEnd::requireReadable).doesNotThrowAnyException();
        assertThatThrownBy(new EventWindow(p, Clock.fixed(p.downloadsUntil(), ZoneOffset.UTC))::requireReadable)
                .isInstanceOf(RuntimeFailure.class);
    }
}
