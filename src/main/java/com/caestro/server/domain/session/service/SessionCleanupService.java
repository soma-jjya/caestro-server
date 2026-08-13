package com.caestro.server.domain.session.service;

import com.caestro.server.domain.devicespec.repository.DeviceSpecRepository;
import com.caestro.server.domain.session.repository.SessionRepository;
import com.caestro.server.domain.shot.repository.ShotRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된(생성 후 보관 기간이 지난) 세션과 그 종속 데이터를 정리한다.
 * 실시간 세션은 수 분 내 종료되므로, 생성 시각 기준 보관 기간이 지난 세션은 안전하게 삭제 대상이다.
 *
 * FK 정합성을 위해 삭제 순서를 지킨다.
 * 1) device_specs (session_id NOT NULL) 삭제
 * 2) shots (session_id nullable)는 보존하되 session 참조만 해제 (사용자 콘텐츠 보호)
 * 3) sessions 삭제
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionCleanupService {

    private final SessionRepository sessionRepository;
    private final DeviceSpecRepository deviceSpecRepository;
    private final ShotRepository shotRepository;

    /**
     * 보관 기간(cutoff) 이전에 생성된 세션과 종속 데이터를 정리한다.
     *
     * @param cutoff 이 시각보다 이전에 생성된 세션이 정리 대상
     * @return 삭제된 세션 수
     */
    @Transactional
    public int cleanupSessionsCreatedBefore(LocalDateTime cutoff) {
        int specs = deviceSpecRepository.deleteBySessionCreatedBefore(cutoff);

        // shot은 보존하고 세션 참조만 해제 (공동 소유 콘텐츠 보호)
        int detachedShots = shotRepository.detachFromSessionsCreatedBefore(cutoff);

        int sessions = sessionRepository.deleteByCreatedBefore(cutoff);

        log.info("Session cleanup done (cutoff={}): sessions={}, deviceSpecs={}, detachedShots={}",
                cutoff, sessions, specs, detachedShots);
        return sessions;
    }
}
