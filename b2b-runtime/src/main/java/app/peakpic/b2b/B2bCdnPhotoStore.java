package app.peakpic.b2b;

import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.store.*;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.net.URI;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** Reuses the engine's upload/deletion implementation; reads use the separate B2B CDN. */
@Primary
@Component
@ConditionalOnProperty(name = "fill.image-store", havingValue = "s3")
public class B2bCdnPhotoStore implements FillImageStore {
    private final S3FillImageStore delegate;
    private final S3Client s3;
    private final RuntimeProperties properties;
    private final String cdn;
    public B2bCdnPhotoStore(S3FillImageStore delegate, S3Client s3, RuntimeProperties properties,
            @Value("${catalog.cdn-base-url}") String cdn) {
        this.delegate = delegate; this.s3 = s3; this.properties = properties;
        URI uri = URI.create(cdn);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getQuery() != null
                || uri.getFragment() != null || uri.getUserInfo() != null || !uri.getPath().isEmpty()
                || uri.getHost().equals("di519bna44kn1.cloudfront.net")) {
            throw new IllegalArgumentException("a dedicated B2B HTTPS CDN origin is required");
        }
        this.cdn = cdn;
    }
    public String store(FillSession session, String type, byte[] body) { return delegate.store(session, type, body); }
    public StoredImage load(FillSession session, String id) { return delegate.load(session, id); }
    public void delete(FillSession session, String id) { delegate.delete(session, id); }
    public void deleteBySession(Long sessionId) { delegate.deleteBySession(sessionId); }
    public long countBySession(Long sessionId) { return delegate.countBySession(sessionId); }
    public String urlOf(String code, String id) { return delegate.urlOf(code, id); }
    public Optional<URI> redirectUrl(FillSession session, String id) {
        if (id == null || !id.matches("[A-Za-z0-9]{22}")) throw new CustomException(ErrorCode.FILL_IMAGE_NOT_FOUND);
        String key = properties.prefix() + "/fills/" + session.getId() + "/" + id;
        try { s3.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build()); }
        catch (NoSuchKeyException missing) { throw new CustomException(ErrorCode.FILL_IMAGE_NOT_FOUND); }
        return Optional.of(URI.create(cdn + "/" + key));
    }
}
