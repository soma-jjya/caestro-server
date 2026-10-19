package com.caestro.server.domain.fill.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.service.FillCodeGenerator;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * S3 저장소(#152)가 키 규칙·Content-Type·세션 단위 정리·302 주소를 올바르게 만드는지. S3 호출은 목이다.
 * 실제 버킷 상대 동작은 배포 후 수동 확인(PR 본문 Test Plan).
 */
@ExtendWith(MockitoExtension.class)
class S3FillImageStoreTest {

    @Mock private S3Client s3;
    @Mock private S3Presigner presigner;
    @Mock private FillCodeGenerator codeGenerator;

    private S3FillImageStore store;
    private FillSession session;

    @BeforeEach
    void setUp() {
        store = new S3FillImageStore(s3, presigner, codeGenerator);
        ReflectionTestUtils.setField(store, "bucket", "peakpic-fill-images-test");
        ReflectionTestUtils.setField(store, "keyPrefix", "fills");
        ReflectionTestUtils.setField(store, "presignMinutes", 10L);
        ReflectionTestUtils.setField(store, "publicBaseUrl", "https://api.peakpic.app/");
        session = FillSession.builder().id(42L).code("K7X2MQ").build();
    }

    @Test
    @DisplayName("저장: 키는 {prefix}/{sessionId}/{imageId}, Content-Type을 객체에 싣는다")
    void store_putsWithKeyRuleAndContentType() {
        when(codeGenerator.imageId()).thenReturn("img000000000000000001a");

        String id = store.store(session, "image/jpeg", new byte[]{1, 2, 3});

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(req.capture(), any(RequestBody.class));
        assertThat(id).isEqualTo("img000000000000000001a");
        assertThat(req.getValue().bucket()).isEqualTo("peakpic-fill-images-test");
        assertThat(req.getValue().key()).isEqualTo("fills/42/img000000000000000001a");
        assertThat(req.getValue().contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("302 주소: 존재를 HEAD로 확인한 뒤 설정된 수명의 서명 주소를 만든다")
    void redirectUrl_headsThenPresigns() throws Exception {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        PresignedGetObjectRequest presigned = org.mockito.Mockito.mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://peakpic-fill-images-test.s3.amazonaws.com/fills/42/x?X-Amz-Signature=abc"));
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        var url = store.redirectUrl(session, "x");

        assertThat(url).isPresent();
        assertThat(url.get().toString()).contains("X-Amz-Signature");
        ArgumentCaptor<GetObjectPresignRequest> req = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(req.capture());
        assertThat(req.getValue().signatureDuration().toMinutes()).isEqualTo(10);
        assertThat(req.getValue().getObjectRequest().key()).isEqualTo("fills/42/x");
    }

    @Test
    @DisplayName("302 주소: 없는 id는 서명하지 않고 404 — S3의 403 XML을 클라이언트에 보내지 않는다")
    void redirectUrl_missingKey_is404() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        assertThatThrownBy(() -> store.redirectUrl(session, "nope"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILL_IMAGE_NOT_FOUND);
        verify(presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    @DisplayName("세션 단위 삭제: 접두사로 나열해 한 번에 지운다. 비어 있으면 삭제 호출이 없다")
    void deleteBySession_listsPrefixAndBatchDeletes() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("fills/42/a").build(), S3Object.builder().key("fills/42/b").build())
                .build());

        store.deleteBySession(42L);

        ArgumentCaptor<ListObjectsV2Request> list = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3).listObjectsV2(list.capture());
        assertThat(list.getValue().prefix()).isEqualTo("fills/42/");
        ArgumentCaptor<DeleteObjectsRequest> del = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3).deleteObjects(del.capture());
        assertThat(del.getValue().delete().objects()).extracting("key").containsExactly("fills/42/a", "fills/42/b");

        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder().contents(List.of()).build());
        store.deleteBySession(43L);
        verify(s3, org.mockito.Mockito.times(1)).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    @DisplayName("단건 삭제: null id는 조용히 넘기고, 있으면 키 규칙대로 지운다")
    void delete_nullIsNoop() {
        store.delete(session, null);
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));

        store.delete(session, "x");
        ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(req.capture());
        assertThat(req.getValue().key()).isEqualTo("fills/42/x");
    }

    @Test
    @DisplayName("주소 형식은 RDS 구현과 같다 — 저장소가 바뀌어도 클라이언트 계약은 그대로")
    void urlOf_sameContractAsRds() {
        assertThat(store.urlOf("K7X2MQ", "x")).isEqualTo("https://api.peakpic.app/fills/K7X2MQ/images/x");
    }
}
