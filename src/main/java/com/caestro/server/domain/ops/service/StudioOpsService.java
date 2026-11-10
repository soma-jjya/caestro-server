package com.caestro.server.domain.ops.service;

import com.caestro.server.domain.ops.dto.response.OpsProResponse;
import com.caestro.server.domain.ops.dto.response.OpsStatsResponse;
import com.caestro.server.domain.ops.dto.response.OpsSubscriptionPageResponse;
import com.caestro.server.domain.ops.dto.response.OpsSubscriptionResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserDetailResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserPageResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserResponse;
import com.caestro.server.domain.ops.repository.OpsSubscriptionCounts;
import com.caestro.server.domain.ops.repository.OpsSubscriptionRepository;
import com.caestro.server.domain.ops.repository.OpsUserCounts;
import com.caestro.server.domain.ops.repository.OpsUserRepository;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.subscription.entity.Subscription;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import com.caestro.server.domain.subscription.repository.SubscriptionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스튜디오 운영 화면의 읽기 전용 조회 (contracts/studio-ops.md): 가입 경로, 마지막 로그인, Pro 구독.
 * 쓰기는 없다. 구매 토큰은 어떤 응답에도 싣지 않고, 소셜·기기 id 는 가려서 보낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudioOpsService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;
    /** "오늘"이 바뀌는 곳: 한도(daily limit)와 같은 서울 자정. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<String> PROVIDERS = List.of("kakao", "google", "apple", "guest", "deleted");
    /** 상단 숫자는 이 시간 안에서는 다시 세지 않는다. 응답의 generatedAt 이 센 시각이라 화면이 "n 기준"으로 보여 준다 (#257). */
    static final Duration STATS_TTL = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final OpsUserRepository opsUserRepository;
    private final OpsSubscriptionRepository opsSubscriptionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PushTokenRepository pushTokenRepository;
    private final Clock clock;

    private final AtomicReference<CachedStats> statsCache = new AtomicReference<>();

    private record CachedStats(OpsStatsResponse value, Instant computedAt) {
    }

    /**
     * 상단 숫자. 테이블을 users 1번·subscriptions 1번만 통과하고(전에는 count 17번, 전부 풀스캔) 60초 동안 재사용한다.
     * 인스턴스마다 따로 캐시하므로 서버 2대면 DB 는 분당 최대 4번 센다.
     */
    @Transactional(readOnly = true)
    public OpsStatsResponse stats() {
        Instant at = clock.instant();
        CachedStats hit = statsCache.get();
        if (hit != null && hit.computedAt().plus(STATS_TTL).isAfter(at)) return hit.value();
        long started = System.nanoTime();
        OpsStatsResponse fresh = computeStats();
        statsCache.set(new CachedStats(fresh, at));
        // 집계는 users 풀스캔 1번이라 행 수에 비례한다. 이 값이 500ms 를 넘으면 배치 집계 테이블로 바꾼다(#257)
        log.info("ops stats computed: users={} ms={}", fresh.users().total(), (System.nanoTime() - started) / 1_000_000);
        return fresh;
    }

    /** 캐시를 비운다. 테스트와, 숫자를 당장 다시 세야 할 때. */
    public void evictStatsCache() {
        statsCache.set(null);
    }

    private OpsStatsResponse computeStats() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime today = startOfSeoulDay(now);
        LocalDateTime weekAgo = today.minusDays(6);
        OpsUserCounts u = opsUserRepository.countSummary(today, weekAgo);
        OpsStatsResponse.Users users = new OpsStatsResponse.Users(u.total(), u.kakao(), u.google(), u.apple(), u.guest(), u.deleted(),
                u.newToday(), u.newLast7Days(), u.activeToday(), u.activeLast7Days(), u.devices(), u.withoutDevice());
        OpsSubscriptionCounts s = opsSubscriptionRepository.countActive(SubscriptionStatus.ACTIVE, now,
                SubscriptionStore.GOOGLE_PLAY, SubscriptionStore.APP_STORE, SubscriptionStore.MANUAL);
        OpsStatsResponse.Subscriptions subscriptions = new OpsStatsResponse.Subscriptions(
                s.active(), s.googlePlay(), s.appStore(), s.manual(), s.autoRenewOff());
        return new OpsStatsResponse(users, subscriptions, now);
    }

    /**
     * @param q        숫자만이면 id 일치, 아니면 닉네임 부분 일치. 비면 조건 없음
     * @param provider kakao·google·apple·guest·deleted, 그 밖의 값은 조건 없음
     * @param pro      true 지금 쓸 수 있는 구독 있음 / false 없음 / null 상관없음
     */
    @Transactional(readOnly = true)
    public OpsUserPageResponse users(String q, String provider, Boolean pro, Long cursor, Integer size) {
        LocalDateTime now = LocalDateTime.now(clock);
        int pageSize = pageSize(size);
        String query = q == null ? null : q.strip();
        Long id = query != null && !query.isEmpty() && query.chars().allMatch(Character::isDigit) && query.length() <= 18
                ? Long.valueOf(query) : null;
        String nickname = id == null && query != null && !query.isEmpty() ? query : null;
        String filter = provider == null ? null : provider.strip().toLowerCase();
        if (filter != null && !PROVIDERS.contains(filter)) filter = null;
        // 1. 한 건 더 읽어 다음 페이지가 있는지 본다
        List<User> rows = opsUserRepository.findOpsPage(cursor, id, nickname, filter, pro, SubscriptionStatus.ACTIVE, now,
                PageRequest.of(0, pageSize + 1));
        boolean hasNext = rows.size() > pageSize;
        List<User> shown = hasNext ? rows.subList(0, pageSize) : rows;
        // 2. 이 페이지 사용자들의 활성 구독을 한 번에 읽어 사람마다 만료가 가장 늦은 것만 남긴다
        Map<Long, Subscription> best = subscriptionRepository
                .findByUserIdInAndStatusAndExpiresAtAfter(shown.stream().map(User::getId).toList(), SubscriptionStatus.ACTIVE, now)
                .stream()
                .collect(Collectors.toMap(s -> s.getUser().getId(), Function.identity(),
                        (a, b) -> a.getExpiresAt().isAfter(b.getExpiresAt()) ? a : b));
        List<OpsUserResponse> items = shown.stream()
                .map(u -> new OpsUserResponse(u.getId(), providerOf(u), u.getLastPlatform(), shortKey(u.getDeviceKey()), u.getNickname(), u.getCreatedAt(), u.getLastLoginAt(),
                        best.containsKey(u.getId()) ? OpsProResponse.from(best.get(u.getId())) : null))
                .toList();
        return new OpsUserPageResponse(items, hasNext ? shown.get(shown.size() - 1).getId() : null);
    }

    /** @throws CustomException USER_NOT_FOUND */
    @Transactional(readOnly = true)
    public OpsUserDetailResponse user(Long id) {
        LocalDateTime now = LocalDateTime.now(clock);
        User user = userRepository.findById(id).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        List<Subscription> history = subscriptionRepository.findByUserIdOrderByExpiresAtDesc(id);
        OpsProResponse pro = history.stream()
                .filter(s -> s.getStatus() == SubscriptionStatus.ACTIVE && s.getExpiresAt().isAfter(now))
                .max(Comparator.comparing(Subscription::getExpiresAt))
                .map(OpsProResponse::from)
                .orElse(null);
        return new OpsUserDetailResponse(user.getId(), providerOf(user), user.getLastPlatform(), shortKey(user.getDeviceKey()), user.getNickname(), user.getCreatedAt(),
                user.getLastLoginAt(), user.getDeletedAt(), mask(user.getOauthId()), mask(user.getDeviceId()), user.getRole(),
                pushTokenRepository.countByUserIdAndDisabledAtIsNull(id), pro,
                history.stream().map(s -> OpsSubscriptionResponse.of(s, now, null, null, null)).toList());
    }

    @Transactional(readOnly = true)
    public OpsSubscriptionPageResponse subscriptions(SubscriptionStatus status, SubscriptionStore store, Long cursor, Integer size) {
        LocalDateTime now = LocalDateTime.now(clock);
        int pageSize = pageSize(size);
        List<Subscription> rows = subscriptionRepository.findOpsPage(status, store, cursor, PageRequest.of(0, pageSize + 1));
        boolean hasNext = rows.size() > pageSize;
        List<Subscription> shown = hasNext ? rows.subList(0, pageSize) : rows;
        List<OpsSubscriptionResponse> items = shown.stream()
                .map(s -> OpsSubscriptionResponse.of(s, now, s.getUser().getId(), s.getUser().getNickname(), providerOf(s.getUser())))
                .toList();
        return new OpsSubscriptionPageResponse(items, hasNext ? shown.get(shown.size() - 1).getId() : null);
    }

    /** kakao·google·apple, 소셜 연동 전 guest, 탈퇴 deleted(탈퇴하면 provider 도 지워진다). */
    static String providerOf(User user) {
        if (user.isDeleted()) return "deleted";
        return user.isGuest() ? "guest" : user.getOauthProvider();
    }

    /** 기기 키 해시의 앞 16자(64비트): 같은 폰끼리 묶기에 충분하고 화면에도 짧다. */
    static String shortKey(String deviceKey) {
        return deviceKey == null ? null : deviceKey.substring(0, Math.min(16, deviceKey.length()));
    }

    /** 앞 3자·뒤 2자만 남기고 가운데를 가린다. 6자 이하면 전부 가린다. null 은 null. */
    static String mask(String value) {
        if (value == null) return null;
        if (value.length() <= 6) return "*".repeat(value.length());
        return value.substring(0, 3) + "*".repeat(value.length() - 5) + value.substring(value.length() - 2);
    }

    private static int pageSize(Integer size) {
        return size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
    }

    /** 서울 오늘 0시를 서버 시간대의 LocalDateTime 으로 (createdAt·lastLoginAt 은 서버 시간대로 찍힌다). */
    private LocalDateTime startOfSeoulDay(LocalDateTime now) {
        return now.atZone(clock.getZone()).withZoneSameInstant(SEOUL).toLocalDate().atStartOfDay(SEOUL)
                .withZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
