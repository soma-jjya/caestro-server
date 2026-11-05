package com.caestro.server.domain.catalog.store;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.net.URI;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 + CloudFront 구현 (#186 2단계). 객체 키는 {prefix}/{id}, 공개 주소는 CDN 도메인 아래 같은 경로다.
 * 버킷은 비공개이고 CloudFront 만 OAC 로 읽는다. 카탈로그 자산은 누구나 보는 공개·영구 자산이라
 * 함께 채우기의 서명 주소(302, 10분) 대신 CDN 캐시가 핵심이다 — 바이트가 앱 서버·RDS 를 더는 거치지 않는다.
 * 객체는 내용이 바뀌지 않는 난수 id 라 1년 immutable 캐시 헤더를 객체에 싣는다.
 * 생성자는 하나뿐이어야 한다 — 조건부 빈의 생성자 모호성으로 운영 기동이 실패한 전례(#111).
 */
@Component
@ConditionalOnProperty(name = "catalog.asset-store", havingValue = "s3")
public class S3CatalogAssetStore implements CatalogAssetStore {

    static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final S3Client s3;
    private final String bucket;
    private final String keyPrefix;
    private final String cdnBaseUrl;

    public S3CatalogAssetStore(S3Client s3,
                               @Value("${catalog.s3.bucket}") String bucket,
                               @Value("${catalog.s3.key-prefix:catalog}") String keyPrefix,
                               // CloudFront 배포 도메인. 경로 패턴 /{key-prefix}/* 가 이 버킷을 가리킨다
                               @Value("${catalog.cdn-base-url}") String cdnBaseUrl) {
        this.s3 = s3;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix;
        this.cdnBaseUrl = cdnBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public void put(String id, String contentType, byte[] data) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(key(id))
                        .contentType(contentType)
                        .cacheControl(CACHE_CONTROL)
                        .build(),
                RequestBody.fromBytes(data));
    }

    /** 서버로 내려받지 않고 S3 안에서 복사한다. Content-Type·캐시 헤더도 따라온다. */
    @Override
    public void copy(String sourceId, String targetId) {
        try {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(key(sourceId))
                    .destinationBucket(bucket).destinationKey(key(targetId))
                    .build());
        } catch (NoSuchKeyException e) {
            throw new CustomException(ErrorCode.CATALOG_IMAGE_NOT_FOUND);
        }
    }

    /** 서버가 바이트를 들어야 할 때만(드묾). 보통은 redirectUrl 로 CDN 에 보낸다. */
    @Override
    public CatalogAsset load(String id) {
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key(id)).build());
            return new CatalogAsset(id, bytes.response().contentType(), bytes.asByteArray());
        } catch (NoSuchKeyException e) {
            throw new CustomException(ErrorCode.CATALOG_IMAGE_NOT_FOUND);
        }
    }

    /** 메타 행 존재는 서비스가 이미 확인했으므로 HEAD 없이 CDN 주소를 준다(요청당 S3 호출 0회). */
    @Override
    public Optional<URI> redirectUrl(String id) {
        return Optional.of(URI.create(urlOf(id)));
    }

    @Override
    public void delete(String id) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(id)).build());
    }

    @Override
    public String urlOf(String id) {
        return cdnBaseUrl + "/" + key(id);
    }

    private String key(String id) {
        return keyPrefix + "/" + id;
    }
}
