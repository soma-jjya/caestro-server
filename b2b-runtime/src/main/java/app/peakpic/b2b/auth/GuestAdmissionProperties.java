package app.peakpic.b2b.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("guest-admission")
public record GuestAdmissionProperties(int ipLimit, int windowSeconds, List<String> trustedProxyCidrs) {
    public GuestAdmissionProperties {
        if (ipLimit < 1 || ipLimit > 10000 || windowSeconds < 1 || windowSeconds > 3600) {
            throw new IllegalArgumentException("invalid guest IP quota");
        }
        trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : List.copyOf(trustedProxyCidrs);
    }
}
