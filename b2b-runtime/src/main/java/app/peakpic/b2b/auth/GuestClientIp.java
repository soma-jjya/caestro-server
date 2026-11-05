package app.peakpic.b2b.auth;

import app.peakpic.b2b.RuntimeFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/** Walk only explicitly trusted hops, starting with the TCP peer. Never trust a client-supplied leftmost XFF. */
@Component
public class GuestClientIp {
    private final List<IpAddressMatcher> proxies;
    public GuestClientIp(GuestAdmissionProperties properties) {
        proxies = properties.trustedProxyCidrs().stream().filter(s -> !s.isBlank()).map(cidr -> {
            literal(cidr.split("/", -1)[0]);
            return new IpAddressMatcher(cidr);
        }).toList();
    }
    public String resolve(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        if (!trusted(peer)) return peer;
        var headers = Collections.list(request.getHeaders("X-Forwarded-For"));
        if (headers.isEmpty()) return peer;
        if (headers.size() != 1 || headers.getFirst().length() > 2048) throw invalid();
        String[] hops = headers.getFirst().split(",", -1);
        if (hops.length > 32) throw invalid();
        for (int i = hops.length - 1; i >= 0 && trusted(peer); i--) peer = literal(hops[i].strip());
        return peer;
    }
    private boolean trusted(String address) { return proxies.stream().anyMatch(proxy -> proxy.matches(address)); }
    private static String literal(String text) {
        if (text == null || text.isBlank()) throw invalid();
        try {
            if (text.contains(":")) {
                if (!text.matches("[a-fA-F0-9:.]+")) throw invalid();
                return InetAddress.getByName(text).getHostAddress(); // Literal IPv6 only; no hostname/DNS lookup.
            }
            if (!text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw invalid();
            String[] parts = text.split("\\.");
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                int number = Integer.parseInt(parts[i]);
                if (number > 255) throw invalid();
                bytes[i] = (byte) number;
            }
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (java.net.UnknownHostException exception) { throw invalid(); }
    }
    private static RuntimeFailure invalid() { return new RuntimeFailure(400, "클라이언트 주소를 확인할 수 없습니다."); }
}
