package app.peakpic.b2b.http;

import app.peakpic.b2b.RuntimeFailure;
import app.peakpic.b2b.RuntimeProperties;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.domain.catalog.store.CatalogAssetStore;
import com.caestro.server.domain.fill.store.FillImageTypes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FrameUploadService {
    public record Uploaded(String id, String imageUrl, int width, int height, String contentType) {}
    private final CatalogImageRepository images;
    private final CatalogAssetStore store;
    private final RuntimeProperties properties;
    private final Clock clock;
    public FrameUploadService(CatalogImageRepository images, CatalogAssetStore store, RuntimeProperties properties, Clock clock) {
        this.images = images; this.store = store; this.properties = properties; this.clock = clock;
    }
    @Transactional
    public Uploaded upload(byte[] body, String type) {
        if (body == null || body.length > 3145728) throw new RuntimeFailure(413, "이미지는 3MB 이하여야 합니다.");
        String resolved = FillImageTypes.resolve(body, type, Set.of("image/png", "image/jpeg"));
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(body))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new RuntimeFailure(400, "올바른 이미지가 아닙니다.");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 40000000) throw new RuntimeFailure(400, "이미지 크기를 확인해 주세요.");
                reader.read(0); // Validate decoding only after the pixel bound.
                String id = UUID.randomUUID().toString().replace("-", "");
                store.put(id, resolved, body);
                images.save(CatalogImage.builder().id(id).width(width).height(height).contentType(resolved)
                        .sizeBytes(body.length).createdAt(LocalDateTime.now(clock)).build());
                return new Uploaded(id, properties.publicBaseUrl() + "/catalog/images/" + id, width, height, resolved);
            } finally { reader.dispose(); }
        } catch (IOException exception) { throw new RuntimeFailure(400, "올바른 이미지가 아닙니다."); }
    }
}
