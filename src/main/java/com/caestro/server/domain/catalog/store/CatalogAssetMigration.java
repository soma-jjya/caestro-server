package com.caestro.server.domain.catalog.store;

import com.caestro.server.domain.catalog.entity.CatalogBlob;
import com.caestro.server.domain.catalog.repository.CatalogBlobRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * RDS 에 남은 자산 바이트를 S3 로 한 번 옮기는 도구 (#186 3단계). {@code catalog.migrate-legacy=true} 로 띄우면 기동 직후 한 번 돈다.
 * 옛 자리(catalog_images.data · b2b_images.data)와 RDS 저장소(catalog_blobs)를 모두 읽어 {prefix}/{id} 로 올리고,
 * HEAD 로 크기를 대조한 뒤에만 원본을 비운다(옛 컬럼은 NULL, 블롭 행은 삭제). 어느 저장소가 켜져 있든 무관하게 S3 에 직접 쓰므로,
 * 서빙은 RDS 인 채로 먼저 옮기고 다 옮긴 걸 확인한 뒤 catalog.asset-store 를 s3 로 바꾸면 사용자가 깨진 그림을 보는 순간이 없다.
 * 두 번 돌아도 같은 객체를 덮어쓸 뿐이라 안전하다. 인스턴스가 둘이면 둘 다 돌 수 있고 결과는 같다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "catalog.migrate-legacy", havingValue = "true")
public class CatalogAssetMigration implements ApplicationRunner {

    /** 한 번 실행한 결과. moved + failed = 옮길 대상 수. */
    public record Result(int moved, int failed) {
    }

    private record Legacy(String table, String id, String contentType, byte[] data) {
    }

    private static final List<String> LEGACY_TABLES = List.of("catalog_images", "b2b_images");

    private final JdbcTemplate jdbc;
    private final CatalogBlobRepository blobs;
    private final S3Client s3;
    private final String bucket;
    private final String keyPrefix;

    public CatalogAssetMigration(JdbcTemplate jdbc, CatalogBlobRepository blobs, S3Client s3,
                                 @Value("${catalog.s3.bucket}") String bucket,
                                 @Value("${catalog.s3.key-prefix:catalog}") String keyPrefix) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.s3 = s3;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            runOnce();
        } catch (RuntimeException e) {
            // 이전 도구가 기동을 막으면 안 된다: 실패는 로그로 남기고 서버는 뜬다
            log.error("Catalog asset migration aborted: {}", e.toString());
        }
    }

    /** 대상을 하나씩 옮긴다. 테스트가 부를 수 있게 분리했다. */
    public Result runOnce() {
        int moved = 0;
        int failed = 0;
        // 1. 옛 컬럼(1단계 이전 업로드)
        for (String table : LEGACY_TABLES) {
            for (Legacy row : legacyRows(table)) {
                if (upload(row.id(), row.contentType(), row.data())) {
                    jdbc.update("update " + table + " set data = null where id = ?", row.id());
                    moved++;
                } else {
                    failed++;
                }
            }
        }
        // 2. RDS 저장소(1단계 이후 업로드)
        for (CatalogBlob blob : blobs.findAll()) {
            if (upload(blob.getId(), blob.getContentType(), blob.getData())) {
                blobs.delete(blob);
                moved++;
            } else {
                failed++;
            }
        }
        // 앞부분 "Catalog asset migration end"는 운영 확인 절차가 의존하므로 바꾸지 않는다
        log.info("Catalog asset migration end (moved={}, failed={})", moved, failed);
        return new Result(moved, failed);
    }

    private List<Legacy> legacyRows(String table) {
        try {
            return jdbc.query("select id, content_type, data from " + table + " where data is not null",
                    (rs, i) -> new Legacy(table, rs.getString(1), rs.getString(2), rs.getBytes(3)));
        } catch (DataAccessException e) {
            return List.of();   // data 컬럼이 이미 없는 DB: 옮길 것이 없다
        }
    }

    /** 올린 뒤 HEAD 로 크기를 대조한다. 다르거나 예외면 false — 원본을 비우지 않는다. */
    private boolean upload(String id, String contentType, byte[] data) {
        String key = keyPrefix + "/" + id;
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                            .contentType(contentType).cacheControl(S3CatalogAssetStore.CACHE_CONTROL).build(),
                    RequestBody.fromBytes(data));
            Long size = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
            if (size == null || size != data.length) {
                log.warn("Catalog asset migration size mismatch (id={}, local={}, s3={})", id, data.length, size);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Catalog asset migration failed (id={}, cause={}: {})", id, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }
}
