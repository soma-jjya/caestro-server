package com.caestro.server.domain.catalog.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.caestro.server.domain.catalog.repository.CatalogBlobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * [회귀] 설정 한 줄로 저장소가 갈리고, 운영 조건(catalog.asset-store=s3)에서 Spring 이 S3 빈을 실제로 만들 수 있다.
 * 조건부 빈은 그 조건으로 컨텍스트를 띄워 보기 전까진 검증된 적이 없는 코드다 — #111 기동 실패의 교훈.
 */
class CatalogAssetStoreSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Deps.class, RdsCatalogAssetStore.class, S3CatalogAssetStore.class)
            .withPropertyValues("catalog.s3.bucket=peakpic-catalog-assets-test", "catalog.cdn-base-url=https://cdn.test");

    @Test
    @DisplayName("기본(설정 없음)과 rds 는 RDS 구현만 뜬다 — 로컬·CI 는 자격증명 없이 돈다")
    void defaultAndRds_selectRds() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CatalogAssetStore.class);
            assertThat(ctx.getBean(CatalogAssetStore.class)).isInstanceOf(RdsCatalogAssetStore.class);
        });
        runner.withPropertyValues("catalog.asset-store=rds").run(ctx ->
                assertThat(ctx.getBean(CatalogAssetStore.class)).isInstanceOf(RdsCatalogAssetStore.class));
    }

    @Test
    @DisplayName("s3 면 S3 구현만 뜬다 — 운영과 같은 조건으로 빈 생성까지 확인")
    void s3_selectsS3() {
        runner.withPropertyValues("catalog.asset-store=s3").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(CatalogAssetStore.class);
            assertThat(ctx.getBean(CatalogAssetStore.class)).isInstanceOf(S3CatalogAssetStore.class);
            assertThat(ctx.getBean(CatalogAssetStore.class).urlOf("x")).isEqualTo("https://cdn.test/catalog/x");
        });
    }

    @Configuration
    static class Deps {
        @Bean CatalogBlobRepository catalogBlobRepository() { return mock(CatalogBlobRepository.class); }
        @Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
        @Bean S3Client s3Client() { return mock(S3Client.class); }
    }
}
