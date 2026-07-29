package com.caestro.server.domain.devicespec.service;

import com.caestro.server.domain.devicespec.entity.DeviceSpec;
import com.caestro.server.domain.devicespec.repository.DeviceSpecRepository;
import com.caestro.server.domain.session.entity.Session;
import com.caestro.server.domain.session.repository.SessionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceSpecService {

    private final DeviceSpecRepository deviceSpecRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    /**
     * DEVICE_SPEC 메시지로 수신한 기기의 카메라 스펙을 device_specs 테이블에 저장한다.
     * 스펙은 기기(사람)의 속성이므로 클라이언트가 보낸 role이 아니라 인증된 userId를 키로 사용한다.
     * 동일 session + user의 스펙이 이미 있으면 값을 갱신(update)하고, 없으면 새로 저장(insert)하는 Upsert로 동작한다.
     * 저장 조건을 만족하지 못하면 예외를 던지지 않고 로그만 남긴 뒤 저장을 생략한다(relay 흐름을 막지 않기 위함).
     *
     * @param sessionCode   스펙이 속한 세션 코드
     * @param userId        스펙을 보낸 기기(인증된 유저)의 ID
     * @param maxZoom       최대 줌 배율 (nullable)
     * @param minZoom       최소 줌 배율 (nullable)
     * @param screenRatio   화면 비율 (nullable)
     * @param maxResolution 지원 최대 해상도 (nullable)
     * @param osType        OS 종류 (nullable)
     */
    @Transactional
    public void saveDeviceSpec(String sessionCode, Long userId, BigDecimal maxZoom, BigDecimal minZoom,
                               BigDecimal screenRatio, String maxResolution, String osType) {
        // 1. 세션 존재 확인 (없으면 저장 생략, relay는 기존 정책대로 처리)
        Session session = sessionRepository.findBySessionCode(sessionCode).orElse(null);
        if (session == null) {
            log.error("DeviceSpec save skipped: session not found. sessionCode={}", sessionCode);
            return;
        }

        // 2. 인증 유저 확인 (userId 없거나 존재하지 않으면 저장 생략)
        if (userId == null) {
            log.warn("DeviceSpec save skipped: missing userId. sessionCode={}", sessionCode);
            return;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("DeviceSpec save skipped: user not found. sessionCode={}, userId={}", sessionCode, userId);
            return;
        }

        // 3. Upsert: 동일 session+user 스펙이 있으면 갱신, 없으면 신규 저장
        deviceSpecRepository.findBySessionIdAndUserId(session.getId(), userId).ifPresentOrElse(
                // 3-1. 기존 스펙 갱신 (변경 감지로 자동 반영)
                existing -> existing.updateSpec(maxZoom, minZoom, screenRatio, maxResolution, osType),
                // 3-2. 신규 스펙 저장
                () -> deviceSpecRepository.save(DeviceSpec.builder()
                        .session(session)
                        .user(user)
                        .maxZoom(maxZoom)
                        .minZoom(minZoom)
                        .screenRatio(screenRatio)
                        .maxResolution(maxResolution)
                        .osType(osType)
                        .build())
        );

        log.info("DeviceSpec upserted: sessionCode={}, userId={}", sessionCode, userId);
    }

    /**
     * 세션 ID로 저장된 기기 스펙 목록을 조회한다.
     *
     * @param sessionId 조회할 세션의 PK
     * @return 해당 세션에 저장된 기기 스펙 목록 (없으면 빈 리스트)
     */
    @Transactional(readOnly = true)
    public List<DeviceSpec> getDeviceSpecs(Long sessionId) {
        return deviceSpecRepository.findBySessionId(sessionId);
    }
}
