package com.caestro.server.domain.catalog.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.catalog.entity.CatalogBlob;
import com.caestro.server.domain.catalog.repository.CatalogBlobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 이전 도구(#186): RDS 블롭을 S3 로 올리고 크기가 맞을 때만 원본을 지운다. 크기가 다른 건 남기고 실패로 센다.
 * 옛 컬럼(catalog_images.data)이 없는 DB 에서는 그 단계를 조용히 건너뛴다(H2 스키마에 그 테이블이 없다).
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"}, showSql = false)
@ContextConfiguration(classes = CatalogAssetMigrationTest.Config.class)
class CatalogAssetMigrationTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = CatalogBlob.class)
    @EnableJpaRepositories(basePackageClasses = CatalogBlobRepository.class)
    static class Config {
        @Bean S3Client s3Client() { return mock(S3Client.class); }
        @Bean CatalogAssetMigration migration(JdbcTemplate jdbc, CatalogBlobRepository blobs, S3Client s3) {
            return new CatalogAssetMigration(jdbc, blobs, s3, "bucket-test", "catalog");
        }
    }

    @Autowired private CatalogAssetMigration migration;
    @Autowired private CatalogBlobRepository blobs;
    @Autowired private S3Client s3;

    @Test
    @DisplayName("크기가 맞는 블롭은 S3 로 옮기고 지운다. 크기가 다른 블롭은 실패로 세고 남긴다")
    void movesMatchingBlobs_keepsMismatched() {
        blobs.save(CatalogBlob.builder().id("ok").contentType("image/png").data(new byte[]{1, 2, 3}).build());
        blobs.save(CatalogBlob.builder().id("bad").contentType("image/png").data(new byte[]{1, 2, 3, 4, 5}).build());
        given(s3.headObject(any(HeadObjectRequest.class))).willAnswer(inv -> {
            HeadObjectRequest req = inv.getArgument(0);
            // "bad" 는 S3 가 다른 크기를 돌려주는 상황
            return HeadObjectResponse.builder().contentLength(req.key().endsWith("/bad") ? 999L : 3L).build();
        });

        CatalogAssetMigration.Result result = migration.runOnce();

        assertThat(result).isEqualTo(new CatalogAssetMigration.Result(1, 1));
        assertThat(blobs.findById("ok")).isEmpty();
        assertThat(blobs.findById("bad")).as("대조 실패한 건 원본을 남긴다").isPresent();
        verify(s3, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
