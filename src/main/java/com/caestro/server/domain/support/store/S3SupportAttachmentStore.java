package com.caestro.server.domain.support.store;

import com.caestro.server.domain.fill.service.FillCodeGenerator;
import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 문의 사진을 S3 에 두는 구현 (#263). 객체 키는 {prefix}/{ticketId}/{storeId}. 조회는 짧은 수명의 서명 주소로 302 —
 * 네컷 이미지(#152)와 같은 길이다. 기본 버킷은 네컷 버킷이므로 그 버킷의 수명 주기는 {@code fills/} 접두사에만 걸려 있어야 한다.
 * 생성자는 하나뿐이어야 한다(#111 과 같은 이유).
 */
@Component
@ConditionalOnProperty(name = "support.attachment-store", havingValue = "s3")
@RequiredArgsConstructor
public class S3SupportAttachmentStore implements SupportAttachmentStore {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final FillCodeGenerator codeGenerator;

    @Value("${support.s3.bucket:${fill.s3.bucket}}")
    private String bucket;

    @Value("${support.s3.key-prefix:support}")
    private String keyPrefix;

    @Value("${support.s3.presign-minutes:10}")
    private long presignMinutes;

    @Override
    public String store(Long ticketId, String contentType, byte[] body) {
        String storeId = codeGenerator.imageId();
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key(ticketId, storeId)).contentType(contentType).build(),
                RequestBody.fromBytes(body));
        return storeId;
    }

    @Override
    public StoredImage load(Long ticketId, String storeId) {
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key(ticketId, storeId)).build());
            return new StoredImage(storeId, bytes.response().contentType(), bytes.asByteArray());
        } catch (NoSuchKeyException e) {
            throw new CustomException(ErrorCode.SUPPORT_ATTACHMENT_NOT_FOUND);
        }
    }

    /** 존재를 HEAD 로 확인한 뒤 서명 주소 — 없는 키에 S3 의 403 XML 대신 우리 404 를 준다. */
    @Override
    public Optional<URI> redirectUrl(Long ticketId, String storeId) {
        String key = key(ticketId, storeId);
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new CustomException(ErrorCode.SUPPORT_ATTACHMENT_NOT_FOUND);
        }
        URL url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(presignMinutes))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build()).url();
        return Optional.of(URI.create(url.toString()));
    }

    @Override
    public void delete(Long ticketId, String storeId) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(ticketId, storeId)).build());
    }

    @Override
    public void deleteByTicket(Long ticketId) {
        // 문의당 많아야 5장이라 한 페이지로 끝난다
        List<ObjectIdentifier> keys = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket).prefix(keyPrefix + "/" + ticketId + "/").build())
                .contents().stream()
                .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                .toList();
        if (keys.isEmpty()) {
            return;
        }
        s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket).delete(Delete.builder().objects(keys).build()).build());
    }

    private String key(Long ticketId, String storeId) {
        return keyPrefix + "/" + ticketId + "/" + storeId;
    }
}
