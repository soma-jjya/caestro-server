package com.caestro.server.domain.catalog.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** S3 저장소(#186)가 키 규칙·캐시 헤더·CDN 주소·S3 안 복사를 올바르게 만드는지. S3 호출은 목이다. */
@ExtendWith(MockitoExtension.class)
class S3CatalogAssetStoreTest {

    @Mock private S3Client s3;

    private S3CatalogAssetStore store() {
        return new S3CatalogAssetStore(s3, "peakpic-catalog-assets-test", "catalog", "https://cdn.test/");
    }

    @Test
    @DisplayName("저장: 키는 {prefix}/{id}, Content-Type 과 1년 immutable 캐시 헤더를 객체에 싣는다")
    void put_keyTypeAndCacheControl() {
        store().put("img1", "image/png", new byte[]{1, 2, 3});

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(req.capture(), any(RequestBody.class));
        assertThat(req.getValue().bucket()).isEqualTo("peakpic-catalog-assets-test");
        assertThat(req.getValue().key()).isEqualTo("catalog/img1");
        assertThat(req.getValue().contentType()).isEqualTo("image/png");
        assertThat(req.getValue().cacheControl()).isEqualTo("public, max-age=31536000, immutable");
    }

    @Test
    @DisplayName("주소: 응답 imageUrl 과 302 Location 모두 CDN 아래 같은 경로. 요청당 S3 호출은 없다")
    void urlAndRedirect_pointToCdn() {
        S3CatalogAssetStore store = store();

        assertThat(store.urlOf("img1")).isEqualTo("https://cdn.test/catalog/img1");
        assertThat(store.redirectUrl("img1")).contains(java.net.URI.create("https://cdn.test/catalog/img1"));
        org.mockito.Mockito.verifyNoInteractions(s3);
    }

    @Test
    @DisplayName("사본: 서버로 내려받지 않고 S3 안에서 복사한다. 원본이 없으면 404")
    void copy_insideS3() {
        store().copy("img1", "bi_1");

        ArgumentCaptor<CopyObjectRequest> req = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3).copyObject(req.capture());
        assertThat(req.getValue().sourceKey()).isEqualTo("catalog/img1");
        assertThat(req.getValue().destinationKey()).isEqualTo("catalog/bi_1");

        when(s3.copyObject(any(CopyObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThatThrownBy(() -> store().copy("nope", "x"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_IMAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제는 키 규칙대로, 없는 객체 읽기는 404")
    void deleteAndMissingLoad() {
        store().delete("img1");
        ArgumentCaptor<DeleteObjectRequest> del = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(del.capture());
        assertThat(del.getValue().key()).isEqualTo("catalog/img1");

        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThatThrownBy(() -> store().load("nope")).isInstanceOf(CustomException.class);
    }
}
