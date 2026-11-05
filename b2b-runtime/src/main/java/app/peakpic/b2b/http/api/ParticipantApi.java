package app.peakpic.b2b.http.api;
import com.caestro.server.domain.b2b.dto.request.CreateB2bFillRequest;
import com.caestro.server.domain.b2b.dto.response.*;
import com.caestro.server.domain.fill.dto.response.FillResponse;
import org.springframework.http.ResponseEntity;

/** Public project entry and scoped guest participation; see API.md for HTTP contracts. */
public interface ParticipantApi {
    ResponseEntity<B2bPublicProjectResponse> entry();
    ResponseEntity<B2bFillResponse> create(CreateB2bFillRequest request, Long user);
    ResponseEntity<B2bFillResponse> get(String code, Long user);
    ResponseEntity<FillResponse> upload(String code, int index, String name, String type, byte[] body, Long user);
    ResponseEntity<byte[]> photo(String code, String id);
    ResponseEntity<byte[]> frame(String id);
}
