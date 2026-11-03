package com.caestro.server.domain.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.caestro.server.domain.ops.dto.response.OpsStatsResponse;
import com.caestro.server.domain.ops.dto.response.OpsSubscriptionResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserDetailResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserPageResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserResponse;
import com.caestro.server.domain.ops.service.StudioOpsService;
import com.caestro.server.domain.push.entity.PushToken;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionPlan;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import com.caestro.server.domain.subscription.repository.SubscriptionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 실제 쿼리로 운영 조회(contracts/studio-ops.md)의 필터·커서·Pro 판별·요약 숫자·마지막 로그인을 검증한다 (H2). */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"}, showSql = false)
@ContextConfiguration(classes = StudioOpsPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StudioOpsPersistenceTest {

    /** 서울 2026-11-04 12:00 = UTC 03:00. 서버 시간대는 UTC 로 둔다. */
    static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");
    static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EntityScan(basePackageClasses = {User.class, Subscription.class, PushToken.class})
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, SubscriptionRepository.class, PushTokenRepository.class,
            com.caestro.server.domain.ops.repository.OpsUserRepository.class})
    @Import(StudioOpsService.class)
    static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @Autowired StudioOpsService service;
    @Autowired UserRepository users;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired PushTokenRepository pushTokens;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    User kakao;
    User google;
    User guest;
    User left;

    @BeforeEach
    void setUp() {
        // H2 는 VARCHAR 로 둔 enum 칼럼(store)의 CHECK 를 평가하지 못해 "Check constraint invalid" 로 막는다(MySQL 운영 DB 는 정상).
        // 이 테스트는 조회만 보므로 subscriptions 의 CHECK 제약을 걷어 낸다.
        jdbc.queryForList("""
                select constraint_name from information_schema.table_constraints
                where table_name = 'SUBSCRIPTIONS' and constraint_type = 'CHECK'
                """, String.class)
                .forEach(name -> jdbc.execute("alter table subscriptions drop constraint " + name));
        pushTokens.deleteAll();
        subscriptions.deleteAll();
        users.deleteAll();
        kakao = users.save(User.builder().oauthProvider("kakao").oauthId("3812345678").nickname("유자").build());
        google = users.save(User.builder().oauthProvider("google").oauthId("108123456789042").nickname("이상협").build());
        guest = users.save(User.builder().deviceId("device-abcdef-123").build());
        left = users.save(User.builder().oauthProvider("apple").oauthId("000123.abc").nickname("떠난 사람").build());
        left.withdraw(NOW_LOCAL.minusDays(1));
        users.save(left);
        // google: 지금 쓰는 월간 + 지난 주간, kakao: 만료된 것뿐
        subscriptions.save(sub(google, SubscriptionStore.GOOGLE_PLAY, "monthly", SubscriptionStatus.ACTIVE, NOW_LOCAL.plusDays(20), true));
        subscriptions.save(sub(google, SubscriptionStore.GOOGLE_PLAY, "weekly", SubscriptionStatus.EXPIRED, NOW_LOCAL.minusDays(10), false));
        subscriptions.save(sub(kakao, SubscriptionStore.APP_STORE, "yearly", SubscriptionStatus.ACTIVE, NOW_LOCAL.minusHours(1), false));
    }

    @Test
    @DisplayName("목록: 최신순, 가입 경로 필터(guest·deleted 포함), 닉네임 일부·id 검색, Pro 필터, 지금 쓰는 구독만 Pro")
    void usersFilters() {
        OpsUserPageResponse all = service.users(null, null, null, null, null);
        assertThat(all.items()).extracting(OpsUserResponse::id).containsExactly(left.getId(), guest.getId(), google.getId(), kakao.getId());
        assertThat(all.items()).extracting(OpsUserResponse::provider).containsExactly("deleted", "guest", "google", "kakao");
        assertThat(all.nextCursor()).isNull();

        assertThat(ids(service.users(null, "guest", null, null, null))).containsExactly(guest.getId());
        assertThat(ids(service.users(null, "deleted", null, null, null))).containsExactly(left.getId());
        assertThat(ids(service.users(null, "Kakao", null, null, null))).containsExactly(kakao.getId());
        assertThat(ids(service.users(null, "naver", null, null, null))).hasSize(4);   // 모르는 값은 조건 없음
        assertThat(ids(service.users("상협", null, null, null, null))).containsExactly(google.getId());
        assertThat(ids(service.users(String.valueOf(kakao.getId()), null, null, null, null))).containsExactly(kakao.getId());

        // kakao 의 ACTIVE 는 이미 만료 → Pro 아님
        assertThat(ids(service.users(null, null, true, null, null))).containsExactly(google.getId());
        assertThat(ids(service.users(null, null, false, null, null))).containsExactly(left.getId(), guest.getId(), kakao.getId());
        OpsUserResponse row = service.users(null, "google", null, null, null).items().get(0);
        assertThat(row.pro()).isNotNull();
        assertThat(row.pro().basePlanId()).isEqualTo("monthly");
        assertThat(row.pro().store()).isEqualTo(SubscriptionStore.GOOGLE_PLAY);
    }

    @Test
    @DisplayName("커서: 한 건 더 읽어 다음 페이지를 알고, nextCursor 로 이어 읽는다")
    void usersCursor() {
        OpsUserPageResponse first = service.users(null, null, null, null, 3);
        assertThat(first.items()).hasSize(3);
        assertThat(first.nextCursor()).isEqualTo(google.getId());
        OpsUserPageResponse second = service.users(null, null, null, first.nextCursor(), 3);
        assertThat(ids(second)).containsExactly(kakao.getId());
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    @DisplayName("상세: 가린 id, 알림 기기 수, 구독 이력(만료가 늦은 것부터)과 지금의 Pro")
    void userDetail() {
        pushTokens.save(PushToken.builder().user(google).platform(com.caestro.server.domain.push.enums.PushPlatform.ANDROID)
                .token("fcm-token-1").lastSeenAt(NOW_LOCAL).build());
        OpsUserDetailResponse detail = service.user(google.getId());
        assertThat(detail.provider()).isEqualTo("google");
        assertThat(detail.oauthIdMasked()).isEqualTo("108**********42");
        assertThat(detail.deviceIdMasked()).isNull();
        assertThat(detail.pushTokens()).isEqualTo(1);
        assertThat(detail.subscriptions()).extracting(OpsSubscriptionResponse::basePlanId).containsExactly("monthly", "weekly");
        assertThat(detail.subscriptions()).extracting(OpsSubscriptionResponse::active).containsExactly(true, false);
        assertThat(detail.pro().basePlanId()).isEqualTo("monthly");

        assertThat(service.user(guest.getId()).deviceIdMasked()).isEqualTo("dev************23");
        assertThat(service.user(kakao.getId()).pro()).isNull();
    }

    @Test
    @DisplayName("구독 목록: 상태·스토어 필터, 사용자 칸, 만료 지난 ACTIVE 는 active=false")
    void subscriptionList() {
        assertThat(service.subscriptions(null, null, null, null).items()).hasSize(3);
        assertThat(service.subscriptions(SubscriptionStatus.ACTIVE, null, null, null).items())
                .extracting(OpsSubscriptionResponse::active).containsExactly(false, true);   // 최신순: kakao(만료 지남), google
        OpsSubscriptionResponse appStore = service.subscriptions(null, SubscriptionStore.APP_STORE, null, null).items().get(0);
        assertThat(appStore.userId()).isEqualTo(kakao.getId());
        assertThat(appStore.nickname()).isEqualTo("유자");
        assertThat(appStore.provider()).isEqualTo("kakao");
    }

    @Test
    @DisplayName("요약: 가입 경로별(탈퇴 제외)·탈퇴·신규·활성(마지막 로그인), 활성 구독 수와 스토어별·해지 예약")
    void stats() {
        users.touchLastLogin(google.getId(), NOW_LOCAL.minusHours(1), "ANDROID"); // 오늘
        users.touchLastLogin(kakao.getId(), NOW_LOCAL.minusDays(3), null);         // 7일 안
        users.touchLastLogin(guest.getId(), NOW_LOCAL.minusDays(30), "IOS");       // 밖
        OpsStatsResponse stats = service.stats();
        assertThat(stats.users().total()).isEqualTo(4);
        assertThat(stats.users().kakao()).isEqualTo(1);
        assertThat(stats.users().google()).isEqualTo(1);
        assertThat(stats.users().apple()).isZero();                              // 탈퇴하면 provider 가 지워진다
        assertThat(stats.users().guest()).isEqualTo(1);
        assertThat(stats.users().deleted()).isEqualTo(1);
        assertThat(stats.users().newToday()).isEqualTo(4);                        // 감사(@CreatedDate)가 실제 시각으로 찍는다
        assertThat(stats.users().activeToday()).isEqualTo(1);
        assertThat(stats.users().activeLast7Days()).isEqualTo(2);
        assertThat(stats.subscriptions().active()).isEqualTo(1);
        assertThat(stats.subscriptions().googlePlay()).isEqualTo(1);
        assertThat(stats.subscriptions().appStore()).isZero();
        assertThat(stats.subscriptions().autoRenewOff()).isZero();
        assertThat(users.findById(google.getId()).orElseThrow().getLastLoginAt()).isEqualTo(NOW_LOCAL.minusHours(1));
    }

    @Test
    @DisplayName("온 앱: 로그인 때 기록하고, 알 수 없는 요청(null)은 이전 값을 덮지 않는다. 목록·상세에 실린다")
    void platform() {
        users.touchLastLogin(guest.getId(), NOW_LOCAL.minusHours(2), "IOS");
        users.touchLastLogin(guest.getId(), NOW_LOCAL.minusHours(1), null);
        assertThat(users.findById(guest.getId()).orElseThrow().getLastPlatform()).isEqualTo("IOS");
        assertThat(service.users(null, "guest", null, null, null).items().get(0).platform()).isEqualTo("IOS");
        assertThat(service.user(guest.getId()).platform()).isEqualTo("IOS");
        assertThat(service.user(kakao.getId()).platform()).isNull();
    }

    private static java.util.List<Long> ids(OpsUserPageResponse page) {
        return page.items().stream().map(OpsUserResponse::id).toList();
    }

    private static Subscription sub(User user, SubscriptionStore store, String plan, SubscriptionStatus status,
                                    LocalDateTime expiresAt, boolean autoRenewing) {
        return Subscription.builder().user(user).plan(SubscriptionPlan.PREMIUM).status(status)
                .startedAt(expiresAt.minusDays(30)).expiresAt(expiresAt).store(store).productId("peakpic_pro")
                .basePlanId(plan).storeState("SUBSCRIPTION_STATE_" + status.name()).autoRenewing(autoRenewing)
                .latestOrderId("GPA.0000-" + plan).build();
    }
}
