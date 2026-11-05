package app.peakpic.b2b.http;

import app.peakpic.b2b.EventWindow;
import app.peakpic.b2b.RuntimeFailure;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.domain.catalog.store.CatalogAssetStore;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class FramePreviewController implements app.peakpic.b2b.http.api.FramePreviewApi {
    private final CatalogImageRepository images;
    private final CatalogAssetStore assets;
    private final EventWindow window;
    public FramePreviewController(CatalogImageRepository images, CatalogAssetStore assets, EventWindow window) {
        this.images = images; this.assets = assets; this.window = window;
    }
    @GetMapping("/catalog/images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        window.requireReadable();
        if (!images.existsById(id)) throw new RuntimeFailure(404, "이미지를 찾을 수 없습니다.");
        var redirect = assets.redirectUrl(id);
        if (redirect.isPresent()) return ResponseEntity.status(302).cacheControl(CacheControl.noStore()).location(redirect.get()).build();
        var image = assets.load(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(image.contentType())).body(image.data());
    }
}
