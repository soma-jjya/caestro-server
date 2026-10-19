package com.caestro.server.domain.fill.store;

import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.service.FillCodeGenerator;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * 이미지 바이트를 S3에 두는 구현 (#152). 객체 키는 {prefix}/{sessionId}/{imageId}.
 * 이미지 GET은 바이트를 중계하지 않고 짧은 수명의 서명 주소로 302 — 사진 트래픽이 앱 서버의 스레드·커넥션 풀을
 * 더는 쓰지 않는다. 버킷은 비공개이고 7일 수명 주기가 걸려 있어 만료 정리가 늦어도 객체는 사라진다.
 * 생성자는 하나뿐이어야 한다 — 조건부 빈의 생성자 모호성으로 운영 기동이 실패한 전례(#111)가 있다.
 */
@Component
@ConditionalOnProperty(name = "fill.image-store", havingValue = "s3")
@RequiredArgsConstructor
public class S3FillImageStore implements FillImageStore {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final FillCodeGenerator codeGenerator;

    @Value("${fill.s3.bucket}")
    private String bucket;

    @Value("${fill.s3.key-prefix:fills}")
    private String keyPrefix;

    @Value("${fill.s3.presign-minutes:10}")
    private long presignMinutes;

    @Value("${fill.public-base-url:https://api.peakpic.app}")
    private String publicBaseUrl;

    @Override
    public String store(FillSession session, String contentType, byte[] body) {
        String imageId = codeGenerator.imageId();
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key(session.getId(), imageId))
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(body));
        return imageId;
    }

    @Override
    public StoredImage load(FillSession session, String imageId) {
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key(session.getId(), imageId)).build());
            return new StoredImage(imageId, bytes.response().contentType(), bytes.asByteArray());
        } catch (NoSuchKeyException e) {
            throw new CustomException(ErrorCode.FILL_IMAGE_NOT_FOUND);
        }
    }

    /** 존재를 HEAD로 확인한 뒤 서명 주소를 만든다 — 없는 id에 S3의 403 XML 대신 우리 404를 준다. */
    @Override
    public Optional<URI> redirectUrl(FillSession session, String imageId) {
        String key = key(session.getId(), imageId);
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new CustomException(ErrorCode.FILL_IMAGE_NOT_FOUND);
        }
        URL url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(presignMinutes))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build()).url();
        return Optional.of(URI.create(url.toString()));
    }

    @Override
    public void delete(FillSession session, String imageId) {
        if (imageId == null) {
            return;
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(session.getId(), imageId)).build());
    }

    @Override
    public void deleteBySession(Long sessionId) {
        List<ObjectIdentifier> keys = list(sessionId).stream()
                .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                .toList();
        if (keys.isEmpty()) {
            return;
        }
        s3.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(keys).build())
                .build());
    }

    @Override
    public long countBySession(Long sessionId) {
        return list(sessionId).size();
    }

    @Override
    public String urlOf(String code, String imageId) {
        return publicBaseUrl.replaceAll("/+$", "") + "/fills/" + code + "/images/" + imageId;
    }

    // 세션 접두사 아래 객체 목록. 세션당 많아야 십수 장이라 한 페이지(최대 1000)로 끝난다
    private List<S3Object> list(Long sessionId) {
        ListObjectsV2Response res = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).prefix(keyPrefix + "/" + sessionId + "/").build());
        return res.contents();
    }

    private String key(Long sessionId, String imageId) {
        return keyPrefix + "/" + sessionId + "/" + imageId;
    }
}
