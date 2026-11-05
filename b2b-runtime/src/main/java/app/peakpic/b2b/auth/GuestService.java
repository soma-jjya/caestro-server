package app.peakpic.b2b.auth;

import app.peakpic.b2b.EventWindow;
import app.peakpic.b2b.RuntimeFailure;
import app.peakpic.b2b.RuntimeProperties;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestService {
    public record Tokens(String accessToken, String refreshToken) {}
    private final UserRepository users;
    private final GuestTokens access;
    private final RefreshTokens refresh;
    private final EventWindow window;
    public GuestService(UserRepository users, GuestTokens access, RefreshTokens refresh,
            EventWindow window) {
        this.users = users; this.access = access; this.refresh = refresh;
        this.window = window;
    }
    /** A caller-provided device ID never grants access to an existing identity. */
    @Transactional
    public Tokens create() {
        window.requireReadable();
        var user = users.saveAndFlush(User.builder().deviceId(UUID.randomUUID().toString()).role(User.Role.USER).build());
        return issue(user.getId());
    }
    public Tokens refresh(String token) {
        window.requireReadable();
        long id = refresh.consume(token);
        if (users.findById(id).filter(u -> !u.isDeleted()).isEmpty()) throw new RuntimeFailure(401, "게스트 인증을 다시 진행해 주세요.");
        return issue(id);
    }
    private Tokens issue(long id) { return new Tokens(access.issue(id), refresh.issue(id)); }
}
