package app.peakpic.b2b;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("runtime")
public record RuntimeProperties(String environment, String projectId, String slug, String runtimeId,
        String apiBaseUrl, Instant startsAt, Instant endsAt, Instant downloadsUntil,
        String jwtSecret, String adminKey, String bucket, String region, int guestLimitPerMinute) {
    public RuntimeProperties {
        require(Set.of("test", "prod").contains(environment), "environment must be test or prod");
        require(projectId != null && projectId.matches("bp_[a-z0-9]{4,32}"), "invalid projectId");
        require(slug != null && slug.matches("[a-z0-9-]{3,40}")
                && !Set.of("b2b", "go", "fill", "admin", "auth", "api", "login", "assets", "static",
                    "frames", "catalog", "www", "app", "health", "studio", "projects").contains(slug), "invalid slug");
        require(runtimeId != null && runtimeId.matches("[a-z0-9]{12,32}"), "invalid runtimeId");
        require(startsAt != null && endsAt != null && downloadsUntil != null
                && startsAt.isBefore(endsAt) && endsAt.isBefore(downloadsUntil), "invalid event/retention dates");
        require(jwtSecret != null && jwtSecret.getBytes(StandardCharsets.UTF_8).length >= 32, "JWT secret too short");
        require(adminKey != null && adminKey.length() >= 32 && !adminKey.equals(jwtSecret), "independent admin key required");
        URI uri = URI.create(apiBaseUrl);
        require(uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                && uri.getFragment() == null && (uri.getPath().isEmpty() || uri.getPath().equals("/")), "API base must be an origin");
        require(uri.getScheme().equals("https") || (environment.equals("test") && uri.getScheme().equals("http")
                && Set.of("localhost", "127.0.0.1").contains(uri.getHost())), "HTTPS required outside local test");
        require(!uri.getHost().equals("api.peakpic.app"), "B2C API origin is forbidden");
        require(bucket != null && bucket.matches("peakpic-b2b-[a-z0-9-]{3,50}"), "a dedicated B2B bucket is required");
        require(region != null && !region.isBlank(), "region required");
        require(guestLimitPerMinute > 0 && guestLimitPerMinute <= 10000, "invalid guest limit");
        apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
    }
    public String prefix() { return "b2b/" + environment + "/" + projectId + "/" + runtimeId; }
    public String issuer() { return "peakpic-b2b:" + environment + ":" + projectId + ":" + runtimeId; }
    public String publicBaseUrl() { return apiBaseUrl + "/projects/" + slug; }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    @Override public String toString() { return "RuntimeProperties[projectId=" + projectId + ", runtimeId=" + runtimeId + "]"; }
}
