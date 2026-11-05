package app.peakpic.b2b.http;

import app.peakpic.b2b.RuntimeFailure;
import app.peakpic.b2b.RuntimeProperties;
import com.caestro.server.domain.b2b.dto.request.*;
import com.caestro.server.domain.b2b.dto.response.B2bProjectResponse;
import com.caestro.server.domain.b2b.service.B2bProjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
public class OperatorController implements app.peakpic.b2b.http.api.OperatorApi {
    private final RuntimeProperties properties;
    private final B2bProjectService projects;
    private final FrameUploadService images;
    private final app.peakpic.b2b.auth.GuestAdmissionProperties admission;
    public OperatorController(RuntimeProperties properties, B2bProjectService projects, FrameUploadService images,
            app.peakpic.b2b.auth.GuestAdmissionProperties admission) {
        this.properties = properties; this.projects = projects; this.images = images; this.admission = admission;
    }
    @GetMapping("/project") public B2bProjectResponse get() { return projects.get(properties.projectId()); }
    public record Identity(String environment, String projectId, String slug, String runtimeId,
                           int guestIpLimit, int guestIpWindowSeconds, int guestRuntimeLimitPerMinute) {}
    @GetMapping("/runtime") public Identity identity() {
        return new Identity(properties.environment(), properties.projectId(), properties.slug(), properties.runtimeId(),
                admission.ipLimit(), admission.windowSeconds(), properties.guestLimitPerMinute());
    }
    @PutMapping("/project") public B2bProjectResponse update(@Valid @RequestBody UpsertB2bProjectRequest request) {
        if (!properties.slug().equals(request.slug())) throw new RuntimeFailure(409, "실행 환경에 연결된 주소는 변경할 수 없습니다.");
        return projects.update(properties.projectId(), request);
    }
    @PutMapping("/project/frames") public B2bProjectResponse frames(@Valid @RequestBody ReplaceB2bFramesRequest request) {
        return projects.replaceFrames(properties.projectId(), request);
    }
    @PostMapping("/project/publish") public B2bProjectResponse publish() { return projects.publish(properties.projectId()); }
    @PostMapping("/project/pause") public B2bProjectResponse pause() { return projects.pause(properties.projectId()); }
    @PostMapping("/project/resume") public B2bProjectResponse resume() { return projects.resume(properties.projectId()); }
    @PostMapping(value = "/images", consumes = {"image/png", "image/jpeg", "application/octet-stream"})
    public FrameUploadService.Uploaded upload(@RequestBody byte[] body, @RequestHeader("Content-Type") String type) {
        return images.upload(body, type);
    }
}
