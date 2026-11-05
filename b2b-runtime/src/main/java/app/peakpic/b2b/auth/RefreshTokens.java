package app.peakpic.b2b.auth;

import app.peakpic.b2b.RuntimeFailure;
import app.peakpic.b2b.RuntimeProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokens {
    private final RedisTemplate<String, String> redis;
    private final RuntimeProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public RefreshTokens(RedisTemplate<String, String> redis, RuntimeProperties properties, Clock clock) {
        this.redis = redis; this.properties = properties; this.clock = clock;
    }
    public String issue(long userId) {
        var ttl = Duration.between(clock.instant(), properties.downloadsUntil());
        if (ttl.isNegative() || ttl.isZero()) throw new RuntimeFailure(410, "사진 보관 기간이 종료되었습니다.");
        ttl = ttl.compareTo(Duration.ofDays(7)) > 0 ? Duration.ofDays(7) : ttl;
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(token), Long.toString(userId), ttl);
        return token;
    }
    /** GETDEL makes a refresh token single use, including concurrent refreshes. */
    public long consume(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        String userId = redis.opsForValue().getAndDelete(key(token));
        if (userId == null) throw invalid();
        return Long.parseLong(userId);
    }
    String key(String token) {
        try {
            return properties.issuer() + ":refresh:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private RuntimeFailure invalid() { return new RuntimeFailure(401, "게스트 인증을 다시 진행해 주세요."); }
}
