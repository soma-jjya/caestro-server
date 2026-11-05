package app.peakpic.b2b.http.api;
import org.springframework.http.ResponseEntity;
/** Random upload-ID preview within the current runtime. */
public interface FramePreviewApi { ResponseEntity<byte[]> image(String id); }
