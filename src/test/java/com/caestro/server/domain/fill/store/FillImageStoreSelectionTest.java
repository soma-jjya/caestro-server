package com.caestro.server.domain.fill.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.caestro.server.domain.fill.repository.FillImageRepository;
import com.caestro.server.domain.fill.service.FillCodeGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * [회귀] 설정 한 줄로 저장소가 갈리고, 운영 조건(fill.image-store=s3)에서 Spring이 S3 빈을 실제로 만들 수 있다.
 * 조건부 빈은 그 조건으로 컨텍스트를 띄워 보기 전까진 검증된 적이 없는 코드다 — #111 기동 실패의 교훈.
 */
class FillImageStoreSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Deps.class, RdsFillImageStore.class, S3FillImageStore.class)
            .withPropertyValues("fill.s3.bucket=peakpic-fill-images-test");

    @Test
    @DisplayName("기본(설정 없음)과 rds는 RDS 구현만 뜬다 — 로컬·CI는 자격증명 없이 돈다")
    void defaultAndRds_selectRds() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(FillImageStore.class);
            assertThat(ctx.getBean(FillImageStore.class)).isInstanceOf(RdsFillImageStore.class);
        });
        runner.withPropertyValues("fill.image-store=rds").run(ctx ->
                assertThat(ctx.getBean(FillImageStore.class)).isInstanceOf(RdsFillImageStore.class));
    }

    @Test
    @DisplayName("s3면 S3 구현만 뜬다 — 운영과 같은 조건으로 빈 생성까지 확인")
    void s3_selectsS3() {
        runner.withPropertyValues("fill.image-store=s3").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(FillImageStore.class);
            assertThat(ctx.getBean(FillImageStore.class)).isInstanceOf(S3FillImageStore.class);
        });
    }

    @Configuration
    static class Deps {
        @Bean FillImageRepository fillImageRepository() { return mock(FillImageRepository.class); }
        @Bean FillCodeGenerator fillCodeGenerator() { return new FillCodeGenerator(); }
        @Bean S3Client s3Client() { return mock(S3Client.class); }
        @Bean S3Presigner s3Presigner() { return mock(S3Presigner.class); }
    }
}
