package app.peakpic.b2b.http.api;
import app.peakpic.b2b.auth.GuestController;
import app.peakpic.b2b.auth.GuestService;
import org.springframework.http.ResponseEntity;
/** Independent guest identity; neither B2C tokens nor client device IDs authenticate a user. */
public interface GuestApi {
    ResponseEntity<GuestService.Tokens> create(jakarta.servlet.http.HttpServletRequest request);
    ResponseEntity<GuestService.Tokens> refresh(GuestController.Refresh request);
}
