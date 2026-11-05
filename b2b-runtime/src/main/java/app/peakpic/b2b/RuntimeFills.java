package app.peakpic.b2b;

import com.caestro.server.domain.b2b.dto.request.CreateB2bFillRequest;
import com.caestro.server.domain.b2b.dto.response.B2bFillResponse;
import com.caestro.server.domain.b2b.service.B2bFillService;
import com.caestro.server.domain.fill.service.FillWriteGuard;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuntimeFills {
    private final B2bFillService engine;
    private final FillWriteGuard guard;
    private final EventWindow window;
    private final RuntimeProperties properties;
    private final EntityManager entityManager;
    public RuntimeFills(B2bFillService engine, FillWriteGuard guard, EventWindow window,
            RuntimeProperties properties, EntityManager entityManager) {
        this.engine = engine; this.guard = guard; this.window = window;
        this.properties = properties; this.entityManager = entityManager;
    }
    /** Persist the event retention deadline; the B2C seven-day default is unchanged. */
    @Transactional
    public B2bFillResponse create(Long user, CreateB2bFillRequest request) {
        window.requireWritable(); guard.checkCreate(user);
        var created = engine.create(properties.slug(), user, request);
        entityManager.flush();
        entityManager.createQuery("update FillSession s set s.expiresAt = :deadline where s.code = :code")
                .setParameter("deadline", LocalDateTime.ofInstant(properties.downloadsUntil(), ZoneOffset.UTC))
                .setParameter("code", created.fill().code()).executeUpdate();
        entityManager.clear();
        return engine.get(properties.slug(), created.fill().code(), user);
    }
}
