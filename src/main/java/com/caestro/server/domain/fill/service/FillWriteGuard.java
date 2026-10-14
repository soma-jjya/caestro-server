package com.caestro.server.domain.fill.service;

import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 함께 채우기 쓰기 요청의 양을 제한한다 (#150). 게스트 토큰은 deviceId만으로 발급되므로 토큰 하나로
 * 세션·사진을 무한히 쌓아 저장소(RDS 자동 확장 → 비용)를 채우는 공격이 가능했다.
 * 속도는 Redis 고정 창 리미터(#125)로, 총량은 사용자당 활성 세션 수로 막는다. 거부는 kind 태그로 집계해
 * 정상 사용자가 걸리는지(오탐) Grafana에서 보고 한도를 교정한다 — 처음 숫자는 추정치다.
 */
@Component
@RequiredArgsConstructor
public class FillWriteGuard {

    static final String METRIC = "fill.rate_limited";

    private final RedisRateLimiter rateLimiter;
    private final FillSessionRepository sessionRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // 세션 생성: 정상 사용자는 하루에 몇 개 수준 → 시간당 10개면 넉넉하고 스크립트는 바로 걸린다
    @Value("${fill.ratelimit.create-limit:10}")
    private int createLimit;

    @Value("${fill.ratelimit.create-window-seconds:3600}")
    private int createWindowSeconds;

    // 업로드(칸 사진·프레임 요소·GIF 합산): 한 세션을 다 채워도 10회 안팎 → 분당 30개
    @Value("${fill.ratelimit.upload-limit:30}")
    private int uploadLimit;

    @Value("${fill.ratelimit.upload-window-seconds:60}")
    private int uploadWindowSeconds;

    // 속도와 별개의 총량 상한: 천천히 쌓아도 여기서 멈춘다. 만료(7일)된 세션은 세지 않는다
    @Value("${fill.max-active-sessions:20}")
    private int maxActiveSessions;

    /**
     * 세션 생성 전 검사: 생성 속도 한도 → 활성 세션 총량 상한 순.
     *
     * @throws CustomException TOO_MANY_REQUESTS - 창 내 생성 횟수 초과
     * @throws CustomException FILL_TOO_MANY_SESSIONS - 만료되지 않은 세션이 상한 이상
     */
    public void checkCreate(Long userId) {
        if (!rateLimiter.tryAcquire("rl:fill:create:" + userId, createLimit, Duration.ofSeconds(createWindowSeconds))) {
            reject("create", ErrorCode.TOO_MANY_REQUESTS);
        }
        if (sessionRepository.countByOwnerIdAndExpiresAtAfter(userId, LocalDateTime.now(clock)) >= maxActiveSessions) {
            reject("session_cap", ErrorCode.FILL_TOO_MANY_SESSIONS);
        }
    }

    /**
     * 업로드(칸 사진·프레임 요소·GIF) 전 검사. 세 경로가 한 예산을 나눠 쓴다.
     *
     * @throws CustomException TOO_MANY_REQUESTS - 창 내 업로드 횟수 초과
     */
    public void checkUpload(Long userId) {
        if (!rateLimiter.tryAcquire("rl:fill:upload:" + userId, uploadLimit, Duration.ofSeconds(uploadWindowSeconds))) {
            reject("upload", ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    private void reject(String kind, ErrorCode code) {
        meterRegistry.counter(METRIC, "kind", kind).increment();
        throw new CustomException(code);
    }
}
