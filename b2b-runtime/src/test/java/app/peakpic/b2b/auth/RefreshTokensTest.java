package app.peakpic.b2b.auth;

import app.peakpic.b2b.RuntimeProperties;
import java.time.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RefreshTokensTest {
    @SuppressWarnings("unchecked")
    @Test void tokensAreOpaqueScopedAndConsumedOnce() {
        var values = new ConcurrentHashMap<String, String>();
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        doAnswer(i -> { values.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(operations).set(anyString(), anyString(), any(Duration.class));
        when(operations.getAndDelete(anyString())).thenAnswer(i -> values.remove(i.getArgument(0)));
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);
        var p = new RuntimeProperties("test", "bp_test1234", "yuja", "abcdef123456", "http://localhost:8080",
                clock.instant().minusSeconds(1), clock.instant().plusSeconds(3600), clock.instant().plusSeconds(86400),
                "only-test-012345678901234567890123456789", "only-admin-012345678901234567890123456789",
                "peakpic-b2b-test-only", "ap-northeast-2", 300);
        var store = new RefreshTokens(redis, p, clock);
        String token = store.issue(7);
        assertThat(token).hasSize(43);
        assertThat(values.keySet()).allMatch(k -> k.startsWith(p.issuer() + ":refresh:") && !k.contains(token));
        assertThat(store.consume(token)).isEqualTo(7);
        assertThatThrownBy(() -> store.consume(token)).isInstanceOf(app.peakpic.b2b.RuntimeFailure.class);
    }
}
