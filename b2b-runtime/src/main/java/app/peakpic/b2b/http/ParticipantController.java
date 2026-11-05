package app.peakpic.b2b.http;

import app.peakpic.b2b.EventWindow;
import app.peakpic.b2b.RuntimeFailure;
import app.peakpic.b2b.RuntimeProperties;
import app.peakpic.b2b.RuntimeFills;
import com.caestro.server.domain.b2b.dto.request.CreateB2bFillRequest;
import com.caestro.server.domain.b2b.dto.response.B2bFillResponse;
import com.caestro.server.domain.b2b.dto.response.B2bPublicProjectResponse;
import com.caestro.server.domain.b2b.repository.B2bFillRepository;
import com.caestro.server.domain.b2b.service.*;
import com.caestro.server.domain.fill.dto.response.FillResponse;
import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.domain.fill.service.FillWriteGuard;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** All paths are under the immutable servlet context /projects/{slug}. */
@RestController
public class ParticipantController implements app.peakpic.b2b.http.api.ParticipantApi {
    private final RuntimeProperties properties;
    private final EventWindow window;
    private final B2bProjectService projects;
    private final B2bFillService sessions;
    private final B2bFillRepository ownership;
    private final B2bImageService images;
    private final FillService fills;
    private final FillWriteGuard guard;
    private final RuntimeFills runtimeFills;
    private final B2bMetrics metrics;
    public ParticipantController(RuntimeProperties properties, EventWindow window, B2bProjectService projects,
            B2bFillService sessions, B2bFillRepository ownership, B2bImageService images, FillService fills, FillWriteGuard guard, RuntimeFills runtimeFills, B2bMetrics metrics) {
        this.properties = properties; this.window = window; this.projects = projects; this.sessions = sessions;
        this.ownership = ownership; this.images = images; this.fills = fills; this.guard = guard;
        this.runtimeFills = runtimeFills;
        this.metrics = metrics;
    }
    @GetMapping("/")
    public ResponseEntity<B2bPublicProjectResponse> entry() {
        window.requireWritable();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(metrics.observe(B2bMetrics.Operation.ENTRY,
                () -> projects.publicProject(properties.slug())));
    }
    @PostMapping("/fills")
    public ResponseEntity<B2bFillResponse> create(@Valid @RequestBody CreateB2bFillRequest request, @AuthenticationPrincipal Long user) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(metrics.observe(B2bMetrics.Operation.CREATE,
                () -> runtimeFills.create(user, request)));
    }
    @GetMapping("/fills/{code}")
    public ResponseEntity<B2bFillResponse> get(@PathVariable String code, @AuthenticationPrincipal Long user) {
        window.requireReadable();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(metrics.observe(B2bMetrics.Operation.READ,
                () -> sessions.get(properties.slug(), code, user)));
    }
    @PutMapping(value = "/fills/{code}/slots/{index}", consumes = {"image/jpeg", "image/png", "application/octet-stream"})
    public ResponseEntity<FillResponse> upload(@PathVariable String code, @PathVariable int index,
            @RequestParam(required = false) String name, @RequestHeader(value = "Content-Type", required = false) String type,
            @RequestBody byte[] body, @AuthenticationPrincipal Long user) {
        window.requireWritable(); requireOwned(code); guard.checkUpload(user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(fills.fillSlot(code, index, user, name, body, type));
    }
    @GetMapping("/fills/{code}/images/{id}")
    public ResponseEntity<byte[]> photo(@PathVariable String code, @PathVariable String id) {
        window.requireReadable(); requireOwned(code);
        var redirect = fills.imageRedirect(code, id);
        if (redirect.isPresent()) return ResponseEntity.status(302).cacheControl(CacheControl.noStore()).location(redirect.get()).build();
        var image = fills.image(code, id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(image.contentType())).body(image.data());
    }
    @GetMapping("/b2b/images/{id}")
    public ResponseEntity<byte[]> frame(@PathVariable String id) {
        window.requireReadable();
        var redirect = images.imageRedirect(id);
        if (redirect.isPresent()) return ResponseEntity.status(302).cacheControl(CacheControl.noStore()).location(redirect.get()).build();
        var image = images.image(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(image.contentType())).body(image.data());
    }
    private void requireOwned(String code) {
        if (ownership.findByProjectSlugAndSessionCode(properties.slug(), code.strip().toUpperCase(Locale.ROOT)).isEmpty()) {
            throw new RuntimeFailure(404, "네컷을 찾을 수 없습니다.");
        }
    }
}
