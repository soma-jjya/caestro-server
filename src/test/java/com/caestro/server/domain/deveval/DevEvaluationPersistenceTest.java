package com.caestro.server.domain.deveval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caestro.server.domain.deveval.dto.PhotoScoreDto;
import com.caestro.server.domain.deveval.dto.request.UpsertDevEvaluationRequest;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationPageResponse;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationRowResponse;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationSavedResponse;
import com.caestro.server.domain.deveval.dto.response.DevEvaluationStatsResponse;
import com.caestro.server.domain.deveval.entity.DevEvaluation;
import com.caestro.server.domain.deveval.entity.DevEvaluationPhoto;
import com.caestro.server.domain.deveval.repository.DevEvaluationPhotoRepository;
import com.caestro.server.domain.deveval.repository.DevEvaluationRepository;
import com.caestro.server.domain.deveval.service.DevEvaluationService;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 쿼리로 개발자 평가(contracts/dev-evaluations.md)를 본다 (H2): clientId 덮어쓰기와 주인 확인, 사용자당 상한,
 * 사진은 기록 뒤에만·바꾸면 하나만 남음, 관리자 목록(거르기·페이지·닉네임·기기 키), 요약 숫자, CSV, 지우기.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"}, showSql = false)
@ContextConfiguration(classes = DevEvaluationPersistenceTest.Config.class)
@TestPropertySource(properties = {"dev-eval.max-per-user=3", "dev-eval.max-photo-bytes=1024"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DevEvaluationPersistenceTest {

    static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    static final String DEVICE_KEY = "3f9a0c1d2e4b5a69" + "0".repeat(48);

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EntityScan(basePackageClasses = {DevEvaluation.class, User.class})
    @EnableJpaRepositories(basePackageClasses = {DevEvaluationRepository.class, UserRepository.class})
    @Import(DevEvaluationService.class)
    static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Autowired DevEvaluationService service;
    @Autowired DevEvaluationRepository evaluations;
    @Autowired DevEvaluationPhotoRepository photos;
    @Autowired UserRepository users;

    User dev;
    User other;

    @BeforeEach
    void setUp() {
        photos.deleteAll();
        evaluations.deleteAll();
        users.deleteAll();
        dev = users.save(User.builder().oauthProvider("google").oauthId("1081234").nickname("이상협").build());
        users.touchLastLogin(dev.getId(), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), "ANDROID", DEVICE_KEY);
        other = users.save(User.builder().deviceId("guest-device-1").build());
    }

    static UpsertDevEvaluationRequest record(int rating, boolean guide) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("lens", "0.6x");
        extra.put("iso", 100);
        return new UpsertDevEvaluationRequest(rating, 30, guide, guide ? "v012" : null, guide ? "shot_0003" : null,
                guide ? "photo" : null, 81.0, new PhotoScoreDto(78.0, 84.0, 0.97, null), "photo",
                "2026-11-10T16:12:00+09:00", "2026-11-10T07:30:00Z", 3000, 4000, "1.1.0-261110.1639", "SM-S938N",
                "역광, \"그래도\" 좋음", extra);
    }

    static byte[] jpeg(int size) {
        byte[] b = new byte[size];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        return b;
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("같은 clientId 는 덮어쓰고(행 하나), 다른 사용자의 clientId 는 403")
    void upsert_overwritesAndChecksOwner() {
        String id = id();
        DevEvaluationSavedResponse first = service.upsert(dev.getId(), id, record(20, true));
        assertThat(first).isEqualTo(new DevEvaluationSavedResponse(id, false));

        service.upsert(dev.getId(), id, record(25, false));
        assertThat(evaluations.count()).isEqualTo(1);
        DevEvaluation saved = evaluations.findById(id).orElseThrow();
        assertThat(saved.getRating()).isEqualTo(25);
        assertThat(saved.isGuide()).isFalse();
        assertThat(saved.getGuideShot()).as("통째로 바꾼다").isNull();
        assertThat(saved.getTakenAt()).as("+09:00 도 UTC 로 맞춘다").isEqualTo(LocalDateTime.of(2026, 11, 10, 7, 12));

        assertCode(() -> service.upsert(other.getId(), id, record(1, true)), ErrorCode.DEV_EVAL_FORBIDDEN);
        assertThat(evaluations.findById(id).orElseThrow().getRating()).isEqualTo(25);
    }

    @Test
    @DisplayName("사용자당 상한(여기선 3): 넷째 새 기록은 409, 이미 있는 기록 고치기는 된다, 다른 사용자는 따로 센다")
    void upsert_limit() {
        String first = id();
        service.upsert(dev.getId(), first, record(10, true));
        service.upsert(dev.getId(), id(), record(11, true));
        service.upsert(dev.getId(), id(), record(12, true));

        assertCode(() -> service.upsert(dev.getId(), id(), record(13, true)), ErrorCode.DEV_EVAL_LIMIT);
        service.upsert(dev.getId(), first, record(30, true));
        service.upsert(other.getId(), id(), record(5, false));
        assertThat(evaluations.countByUserId(dev.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("값 검증: rating 1..ratingMax, ratingMax 2..100, 필수(guide·mediaType·ratedAt), 시각 형식, extra 4KB, clientId 모양")
    void upsert_validation() {
        String id = id();
        UpsertDevEvaluationRequest ok = record(23, true);
        List<UpsertDevEvaluationRequest> bad = List.of(
                new UpsertDevEvaluationRequest(31, 30, true, null, null, null, null, null, "photo", null, "2026-11-10T07:30:00Z", null, null, null, null, null, null),
                new UpsertDevEvaluationRequest(0, 30, true, null, null, null, null, null, "photo", null, "2026-11-10T07:30:00Z", null, null, null, null, null, null),
                new UpsertDevEvaluationRequest(1, 1, true, null, null, null, null, null, "photo", null, "2026-11-10T07:30:00Z", null, null, null, null, null, null),
                new UpsertDevEvaluationRequest(1, 101, true, null, null, null, null, null, "photo", null, "2026-11-10T07:30:00Z", null, null, null, null, null, null),
                new UpsertDevEvaluationRequest(1, 30, null, null, null, null, null, null, "photo", null, "2026-11-10T07:30:00Z", null, null, null, null, null, null),
                new UpsertDevEvaluationRequest(1, 30, true, null, null, null, null, null, "video", null, "2026-11-10T07:30:00Z", null, null, null, null, null, null),
                new UpsertDevEvaluationRequest(1, 30, true, null, null, null, null, null, "photo", null, null, null, null, null, null, null, null),
                new UpsertDevEvaluationRequest(1, 30, true, null, null, null, null, null, "photo", "어제", "2026-11-10T07:30:00Z", null, null, null, null, null, null),
                new UpsertDevEvaluationRequest(1, 30, true, null, null, null, 101.0, null, "photo", null, "2026-11-10T07:30:00Z", null, null, null, null, null, null),
                new UpsertDevEvaluationRequest(1, 30, true, "v".repeat(41), null, null, null, null, "photo", null, "2026-11-10T07:30:00Z", null, null, null, null, null, null),
                new UpsertDevEvaluationRequest(1, 30, true, null, null, null, null, null, "photo", null, "2026-11-10T07:30:00Z", null, null, null, null, "n".repeat(501), null),
                new UpsertDevEvaluationRequest(1, 30, true, null, null, null, null, null, "photo", null, "2026-11-10T07:30:00Z", 0, null, null, null, null, null),
                new UpsertDevEvaluationRequest(1, 30, true, null, null, null, null, null, "photo", null, "2026-11-10T07:30:00Z", null, null, null, null, null,
                        Map.of("blob", "x".repeat(4100))));
        for (UpsertDevEvaluationRequest r : bad) {
            assertCode(() -> service.upsert(dev.getId(), id, r), ErrorCode.DEV_EVAL_INVALID);
        }
        assertCode(() -> service.upsert(dev.getId(), "short", ok), ErrorCode.DEV_EVAL_INVALID);
        assertCode(() -> service.upsert(dev.getId(), "../../etc", ok), ErrorCode.DEV_EVAL_INVALID);
        assertThat(evaluations.count()).isZero();
    }

    @Test
    @DisplayName("사진: 기록이 먼저(404), 남의 기록 403, JPEG/PNG/WebP 만, 바꾸면 하나만 남고 크기·형식이 기록에 붙는다")
    void photo_upload() {
        String id = id();
        assertCode(() -> service.uploadPhoto(dev.getId(), id, jpeg(100), "image/jpeg"), ErrorCode.DEV_EVAL_NOT_FOUND);

        service.upsert(dev.getId(), id, record(23, true));
        assertCode(() -> service.uploadPhoto(other.getId(), id, jpeg(100), "image/jpeg"), ErrorCode.DEV_EVAL_FORBIDDEN);
        assertCode(() -> service.uploadPhoto(dev.getId(), id, "GIF89a-not-a-photo".getBytes(StandardCharsets.UTF_8), "image/jpeg"),
                ErrorCode.DEV_EVAL_INVALID_PHOTO);
        assertCode(() -> service.uploadPhoto(dev.getId(), id, jpeg(100), "image/gif"), ErrorCode.DEV_EVAL_INVALID_PHOTO);
        assertCode(() -> service.uploadPhoto(dev.getId(), id, jpeg(1025), "image/jpeg"), ErrorCode.DEV_EVAL_PHOTO_TOO_LARGE);

        service.uploadPhoto(dev.getId(), id, jpeg(100), "image/jpeg");
        byte[] png = new byte[200];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        service.uploadPhoto(dev.getId(), id, png, "application/octet-stream");

        assertThat(photos.count()).isEqualTo(1);
        DevEvaluationPhoto photo = service.photo(id);
        assertThat(photo.getContentType()).as("앞 바이트로 본 형식").isEqualTo("image/png");
        assertThat(photo.getData()).hasSize(200);
        DevEvaluation e = evaluations.findById(id).orElseThrow();
        assertThat(e.getPhotoBytes()).isEqualTo(200);
        assertThat(service.upsert(dev.getId(), id, record(24, true)).hasPhoto()).as("기록을 고쳐도 사진은 남는다").isTrue();
    }

    @Test
    @DisplayName("관리자 목록: 최근 고친 것부터, guide·userId 거르기, 페이지, 닉네임·기기 키 앞 16자·사진 크기")
    void admin_list() {
        String a = id();
        String b = id();
        String c = id();
        service.upsert(dev.getId(), a, record(20, true));
        service.upsert(dev.getId(), b, record(10, false));
        service.upsert(other.getId(), c, record(5, true));
        service.uploadPhoto(dev.getId(), a, jpeg(300), "image/jpeg");

        DevEvaluationPageResponse all = service.page(null, null, null, null);
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.size()).isEqualTo(50);
        assertThat(all.items()).extracting(DevEvaluationRowResponse::id).containsExactlyInAnyOrder(a, b, c);

        DevEvaluationPageResponse guided = service.page(0, 1, true, null);
        assertThat(guided.total()).isEqualTo(2);
        assertThat(guided.items()).hasSize(1);
        assertThat(service.page(1, 1, true, null).items()).hasSize(1);

        DevEvaluationRowResponse row = service.page(0, 50, true, dev.getId()).items().get(0);
        assertThat(row.id()).isEqualTo(a);
        assertThat(row.userId()).isEqualTo(dev.getId());
        assertThat(row.userNickname()).isEqualTo("이상협");
        assertThat(row.deviceKey()).isEqualTo("3f9a0c1d2e4b5a69");
        assertThat(row.hasPhoto()).isTrue();
        assertThat(row.photoBytes()).isEqualTo(300);
        assertThat(row.photoScore()).isEqualTo(new PhotoScoreDto(78.0, 84.0, 0.97, null));
        assertThat(row.extra()).containsEntry("lens", "0.6x").containsEntry("iso", 100);
        assertThat(row.ratedAt()).isEqualTo(Instant.parse("2026-11-10T07:30:00Z"));
        assertThat(row.createdAt()).isEqualTo(NOW);

        DevEvaluationRowResponse guest = service.page(0, 50, null, other.getId()).items().get(0);
        assertThat(guest.userNickname()).isNull();
        assertThat(guest.deviceKey()).isNull();
        assertThat(guest.hasPhoto()).isFalse();
        assertThat(guest.photoBytes()).isNull();
        assertCode(() -> service.photo(c), ErrorCode.DEV_EVAL_PHOTO_NOT_FOUND);
    }

    @Test
    @DisplayName("요약: 수·사람·사진, 가이드 있음·없음 평균(만점 맞춤), 가장 많이 쓴 만점. 비어 있으면 평균·만점 null")
    void admin_stats() {
        DevEvaluationStatsResponse empty = service.stats();
        assertThat(empty.count()).isZero();
        assertThat(empty.guide().avgRating()).isNull();
        assertThat(empty.ratingMax()).isNull();

        String a = id();
        service.upsert(dev.getId(), a, record(20, true));
        service.upsert(dev.getId(), id(), record(25, true));
        service.upsert(other.getId(), id(), record(10, false));
        // 10점 만점 5점 = 30점 만점 15점
        service.upsert(other.getId(), id(), new UpsertDevEvaluationRequest(5, 10, false, null, null, null, null, null, "fourcut",
                null, "2026-11-10T07:30:00Z", null, null, null, null, null, null));
        service.uploadPhoto(dev.getId(), a, jpeg(50), "image/jpeg");

        DevEvaluationStatsResponse stats = service.stats();
        assertThat(stats.count()).isEqualTo(4);
        assertThat(stats.users()).isEqualTo(2);
        assertThat(stats.withPhoto()).isEqualTo(1);
        assertThat(stats.ratingMax()).isEqualTo(30);
        assertThat(stats.guide().count()).isEqualTo(2);
        assertThat(stats.guide().avgRating()).isEqualTo(22.5);
        assertThat(stats.noGuide().count()).isEqualTo(2);
        assertThat(stats.noGuide().avgRating()).isEqualTo(12.5);
    }

    @Test
    @DisplayName("CSV: UTF-8 BOM, 머리줄, 기록마다 한 줄, 쉼표·따옴표 감싸기, 수식으로 시작하는 글자 막기")
    void admin_csv() {
        String a = id();
        service.upsert(dev.getId(), a, record(20, true));
        service.upsert(other.getId(), id(), new UpsertDevEvaluationRequest(3, 30, false, null, null, null, null, null, "photo",
                null, "2026-11-10T07:30:00Z", null, null, null, "=HYPERLINK(\"x\")", null, null));

        byte[] csv = service.csv(null, null);
        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
        String text = new String(csv, StandardCharsets.UTF_8).substring(1);
        String[] lines = text.split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).startsWith("id,userId,userNickname,deviceKey,rating,ratingMax,guide,")
                .endsWith(",hasPhoto,photoBytes,createdAt,updatedAt");
        String devLine = lines[1].startsWith(a) ? lines[1] : lines[2];
        assertThat(devLine).contains(",이상협,3f9a0c1d2e4b5a69,20,30,true,v012,shot_0003,photo,81,78,84,0.97,,photo,")
                .contains("\"역광, \"\"그래도\"\" 좋음\"")
                .contains("\"{\"\"lens\"\":\"\"0.6x\"\",\"\"iso\"\":100}\"");
        String guestLine = lines[1].startsWith(a) ? lines[2] : lines[1];
        assertThat(guestLine).contains("\"'=HYPERLINK(\"\"x\"\")\"");

        assertThat(new String(service.csv(true, null), StandardCharsets.UTF_8).split("\r\n")).hasSize(2);
    }

    @Test
    @DisplayName("지우기: 기록과 사진을 함께, 없으면 404")
    void admin_delete() {
        String a = id();
        service.upsert(dev.getId(), a, record(20, true));
        service.uploadPhoto(dev.getId(), a, jpeg(50), "image/jpeg");

        service.delete(a);
        assertThat(evaluations.existsById(a)).isFalse();
        assertThat(photos.existsById(a)).isFalse();
        assertCode(() -> service.delete(a), ErrorCode.DEV_EVAL_NOT_FOUND);
    }
}
