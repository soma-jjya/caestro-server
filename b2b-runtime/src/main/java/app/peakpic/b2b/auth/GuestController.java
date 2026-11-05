package app.peakpic.b2b.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class GuestController implements app.peakpic.b2b.http.api.GuestApi {
    private final GuestService guests;
    private final GuestAdmission admission;
    public GuestController(GuestService guests, GuestAdmission admission) { this.guests = guests; this.admission = admission; }
    public record Refresh(@NotBlank @Size(max = 128) String refreshToken) {}
    @PostMapping("/guest")
    public ResponseEntity<GuestService.Tokens> create(jakarta.servlet.http.HttpServletRequest request) {
        admission.acquire(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(guests.create());
    }
    @PostMapping("/refresh")
    public ResponseEntity<GuestService.Tokens> refresh(@Valid @RequestBody Refresh request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(guests.refresh(request.refreshToken()));
    }
}
