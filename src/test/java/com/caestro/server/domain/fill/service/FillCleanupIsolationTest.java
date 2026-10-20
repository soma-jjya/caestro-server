package com.caestro.server.domain.fill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.caestro.server.domain.fill.entity.FillImage;
import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.repository.FillImageRepository;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * #162 재현 — 2026-10-20 04:30 운영 배치 실패 그대로.
 * 저장소가 S3로 바뀐 뒤 옛 `fill_images` 행이 남은 세션은 외래키(session_id → fill_sessions) 때문에 세션 삭제가 실패한다.
 * 문제는 그 한 세션이 아니라, 배치가 트랜잭션 하나라 **다른 만료 세션까지 전부 롤백**되는 것이다.
 * 실제 MySQL 필요 (docker-compose up -d / CI 서비스 컨테이너). S3는 목 — 운영 조건(fill.image-store=s3)으로 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "fill.image-store=s3")
class FillCleanupIsolationTest {

    @Autowired private FillCleanupScheduler scheduler;
    @Autowired private FillSessionRepository sessionRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private FillImageRepository imageRepository;
    @Autowired private UserRepository userRepository;

    @MockitoBean private S3Client s3;
    @MockitoBean private S3Presigner presigner;

    private User owner;
    private FillSession legacy;   // 옛 fill_images 행이 남은 세션 — 세션 삭제가 외래키에 걸린다
    private FillSession normal;   // S3 시대 세션 — 아무 문제 없이 지워져야 한다
    private String legacyImageId;

    @BeforeEach
    void setUp() {
        given(s3.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(ListObjectsV2Response.builder().build());
        owner = userRepository.save(User.builder()
                .deviceId("cleanup-" + UUID.randomUUID()).nickname("주인").role(User.Role.USER).build());
        LocalDateTime expired = LocalDateTime.now().minusMinutes(5);
        legacy = sessionRepository.save(session("LEG" + suffix(), expired));
        normal = sessionRepository.save(session("NOR" + suffix(), expired));
        // 옛 저장소 시절의 사진 행. S3 저장소는 이 테이블을 모르므로 지우지 않는다
        legacyImageId = "legacy" + UUID.randomUUID().toString().substring(0, 16);
        imageRepository.save(FillImage.builder().id(legacyImageId)
                .session(legacy).contentType("image/jpeg").data(new byte[]{1, 2, 3}).sizeBytes(3).build());
    }

    @AfterEach
    void cleanup() {
        // 파생 deleteBy 쿼리는 트랜잭션 밖에서 못 쓰므로 단건 delete(자체 트랜잭션)로 정리한다
        imageRepository.findById(legacyImageId).ifPresent(imageRepository::delete);
        sessionRepository.findById(legacy.getId()).ifPresent(sessionRepository::delete);
        sessionRepository.findById(normal.getId()).ifPresent(sessionRepository::delete);
        userRepository.delete(owner);
    }

    @Test
    @DisplayName("[재현→방어] 외래키에 걸린 세션이 하나 있어도 다른 만료 세션은 지워진다")
    void oneFailingSession_doesNotBlockOthers() {
        double failedBefore = meterRegistry.counter("fill.cleanup.failed").count();

        FillCleanupScheduler.Result result = scheduler.runOnce(LocalDateTime.now());

        assertThat(sessionRepository.findById(normal.getId())).as("정상 세션은 지워져야 한다").isEmpty();
        assertThat(sessionRepository.findById(legacy.getId())).as("외래키에 걸린 세션은 남는다(실패로 집계)").isPresent();
        assertThat(result.purged()).isGreaterThanOrEqualTo(1);
        assertThat(result.failed()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.counter("fill.cleanup.failed").count()).isGreaterThan(failedBefore);
    }

    private FillSession session(String code, LocalDateTime expiresAt) {
        return FillSession.builder().code(code).owner(owner).frame("Black").layout("Strip").expiresAt(expiresAt).build();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 3).toUpperCase();
    }
}
