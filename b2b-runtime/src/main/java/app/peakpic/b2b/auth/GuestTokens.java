package app.peakpic.b2b.auth;

import app.peakpic.b2b.RuntimeProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

/** Tokens have no authority outside this immutable project/runtime binding. */
@Component
public class GuestTokens {
    private final RuntimeProperties properties;
    private final Clock clock;
    private final SecretKey key;
    public GuestTokens(RuntimeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }
    public String issue(long userId) {
        var expiration = clock.instant().plus(Duration.ofMinutes(15));
        if (expiration.isAfter(properties.downloadsUntil())) expiration = properties.downloadsUntil();
        return Jwts.builder().issuer(properties.issuer()).subject(Long.toString(userId))
                .audience().add("peakpic-b2b").and().claim("projectId", properties.projectId())
                .claim("kind", "guest-access").id(UUID.randomUUID().toString())
                .issuedAt(Date.from(clock.instant())).expiration(Date.from(expiration)).signWith(key).compact();
    }
    public long verify(String token) {
        var claims = Jwts.parser().verifyWith(key).requireIssuer(properties.issuer())
                .requireAudience("peakpic-b2b").require("projectId", properties.projectId())
                .require("kind", "guest-access").clock(() -> Date.from(clock.instant())).build()
                .parseSignedClaims(token).getPayload();
        long userId = Long.parseLong(claims.getSubject());
        if (claims.getExpiration() == null || claims.getIssuedAt() == null || userId < 1) {
            throw new IllegalArgumentException("invalid access token");
        }
        return userId;
    }
}
