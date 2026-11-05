package app.peakpic.b2b;

import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.service.FillCodeGenerator;
import com.caestro.server.domain.fill.store.S3FillImageStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StorageScopeTest {
    @Test void actualStoreDeletesOnlyTheBoundRuntimePrefixAndCdnUsesTheSameKey() {
        var p = RuntimeIsolationTest.properties("bp_test1234", "abcdef123456");
        var client = mock(S3Client.class);
        var delegate = new S3FillImageStore(client, mock(S3Presigner.class), new FillCodeGenerator());
        ReflectionTestUtils.setField(delegate, "bucket", p.bucket());
        ReflectionTestUtils.setField(delegate, "keyPrefix", p.prefix() + "/fills");
        String imageId = "a".repeat(22);
        String key = p.prefix() + "/fills/1/" + imageId;
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(List.of(S3Object.builder().key(key).build())).build());
        delegate.deleteBySession(1L);
        var listing = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client).listObjectsV2(listing.capture());
        assertThat(listing.getValue().prefix()).isEqualTo(p.prefix() + "/fills/1/");
        var deletion = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(client).deleteObjects(deletion.capture());
        assertThat(deletion.getValue().delete().objects()).extracting(ObjectIdentifier::key).containsExactly(key);
        var cdn = new B2bCdnPhotoStore(delegate, client, p, "https://b2b-test.example.com");
        assertThat(cdn.redirectUrl(FillSession.builder().id(1L).build(), imageId).orElseThrow().toString())
                .isEqualTo("https://b2b-test.example.com/" + key);
        var read = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(client).headObject(read.capture());
        assertThat(read.getValue().key()).isEqualTo(key);
        assertThatThrownBy(() -> cdn.redirectUrl(FillSession.builder().id(1L).build(), "../../other"))
                .isInstanceOf(RuntimeException.class);
    }
    @Test void initializerRefusesExistingB2cDatabaseBeforeOpeningAConnection() {
        assertThatThrownBy(() -> DatabaseInitializer.initialize(java.util.Map.of(
                "B2B_DB_URL", "jdbc:mysql://localhost:3306/caestro", "B2B_PROJECT_ID", "bp_test1234", "B2B_RUNTIME_ID", "abcdef123456")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
