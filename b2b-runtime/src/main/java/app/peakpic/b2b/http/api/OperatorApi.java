package app.peakpic.b2b.http.api;
import app.peakpic.b2b.http.FrameUploadService;
import app.peakpic.b2b.http.OperatorController;
import com.caestro.server.domain.b2b.dto.request.*;
import com.caestro.server.domain.b2b.dto.response.B2bProjectResponse;

/** Server-to-server operations on the one bound project; X-B2B-Admin-Key required. */
public interface OperatorApi {
    B2bProjectResponse get();
    OperatorController.Identity identity();
    B2bProjectResponse update(UpsertB2bProjectRequest request);
    B2bProjectResponse frames(ReplaceB2bFramesRequest request);
    B2bProjectResponse publish();
    B2bProjectResponse pause();
    B2bProjectResponse resume();
    FrameUploadService.Uploaded upload(byte[] body, String type);
}
