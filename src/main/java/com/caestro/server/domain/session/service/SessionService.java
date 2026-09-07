package com.caestro.server.domain.session.service;

import com.caestro.server.domain.session.entity.Session;
import com.caestro.server.domain.session.repository.SessionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션의 DB 영구 기록. 기록 이벤트(create/join/end)는 비동기 워커로 도착하고, 멀티 인스턴스에서는
 * 서로 다른 서버의 큐를 타므로 도착 순서를 보장할 수 없다 (#124). 그래서 각 기록은 멱등 upsert로
 * 작성한다 — 행이 없으면 이벤트에 동봉된 스냅샷으로 직접 생성하고, 상태는 단조 전이만 허용해
 * 어떤 도착 순서에도 최종 기록이 같아진다. 동시 생성 경합은 sessionCode UNIQUE 제약이 심판하며,
 * 패자의 예외는 디스패처의 재시도가 update 경로로 수렴시킨다.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    /**
     * 방 생성자(owner)가 만든 세션을 DB에 영속화한다.
     * join/end 기록이 먼저 도착해 행을 만들어뒀으면 그대로 사용한다 (멱등).
     *
     * @param sessionCode 발급된 세션 코드
     * @param ownerUserId 세션을 생성한 owner의 유저 ID
     * @param expiresAt   세션 만료 시각
     * @return 저장된(또는 이미 존재하던) Session 엔티티
     * @throws CustomException USER_NOT_FOUND - owner 유저를 찾을 수 없음
     */
    @Transactional
    public Session createSession(String sessionCode, Long ownerUserId, LocalDateTime expiresAt) {
        return sessionRepository.findBySessionCode(sessionCode)
                .orElseGet(() -> insertRow(sessionCode, ownerUserId, expiresAt));
    }

    /**
     * 참여자 입장 마일스톤을 기록한다.
     * create 기록보다 먼저 도착해도(멀티 인스턴스 크로스 기록) 동봉된 스냅샷으로 행을 직접
     * 생성해 유실을 막고, 상태 전이는 엔티티의 단조 규칙을 따른다.
     *
     * @param sessionCode       입장한 세션 코드
     * @param participantUserId 참여자 유저 ID (null 허용)
     * @param cameraMode        카메라 모드 (APP)
     * @param ownerUserId       이벤트 발생 시점 Redis 스냅샷의 owner ID (행 생성용)
     * @param expiresAt         이벤트 발생 시점 Redis 스냅샷의 만료 시각 (행 생성용)
     * @throws CustomException SESSION_NOT_FOUND - 행이 없고 스냅샷도 없어 생성 불가 (재시도 대상)
     * @throws CustomException USER_NOT_FOUND - 참여자/owner 유저를 찾을 수 없음
     */
    @Transactional
    public void joinSession(String sessionCode, Long participantUserId, String cameraMode,
            Long ownerUserId, LocalDateTime expiresAt) {
        // 1. 행 확보 — 없으면 스냅샷으로 직접 생성 (upsert)
        Session session = sessionRepository.findBySessionCode(sessionCode)
                .orElseGet(() -> insertRow(sessionCode, ownerUserId, expiresAt));

        // 2. 참여자 유저 조회
        User participant = null;
        if (participantUserId != null) {
            participant = userRepository.findById(participantUserId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        }

        // 3. 연결 마일스톤 반영 (단조 — 늦은 도착이 ENDED를 되살리지 않는다)
        session.connect(participant, cameraMode);
    }

    /**
     * 종료 마일스톤을 기록한다. 행이 없으면(극단적 순서 역전) 스냅샷으로 생성 후 종료 처리한다.
     *
     * @param sessionCode 종료할 세션 코드
     * @param ownerUserId 이벤트 발생 시점 Redis 스냅샷의 owner ID (행 생성용)
     * @param expiresAt   이벤트 발생 시점 Redis 스냅샷의 만료 시각 (행 생성용)
     */
    @Transactional
    public void endSession(String sessionCode, Long ownerUserId, LocalDateTime expiresAt) {
        Session session = sessionRepository.findBySessionCode(sessionCode)
                .orElseGet(() -> insertRow(sessionCode, ownerUserId, expiresAt));
        session.end();
    }

    /**
     * 스냅샷으로 세션 행을 생성한다. 같은 코드의 동시 insert는 UNIQUE 제약이 한쪽을 탈락시키고,
     * 그 예외는 디스패처 재시도가 재실행해 update 경로로 수렴한다 (멱등이라 안전).
     *
     * @throws CustomException SESSION_NOT_FOUND - 스냅샷 결측으로 생성 불가 (재시도 대상)
     */
    private Session insertRow(String sessionCode, Long ownerUserId, LocalDateTime expiresAt) {
        if (ownerUserId == null || expiresAt == null) {
            throw new CustomException(ErrorCode.SESSION_NOT_FOUND);
        }
        User owner = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        // 즉시 flush: UNIQUE 심판을 커밋 시점이 아니라 여기서 받아야 예외가 재시도 가능한 타입으로 번역된다
        return sessionRepository.saveAndFlush(Session.builder()
                .sessionCode(sessionCode)
                .owner(owner)
                .cameraMode("APP")
                .status("WAITING")
                .expiresAt(expiresAt)
                .build());
    }

    /**
     * 세션 ID로 세션 단건을 조회한다.
     *
     * @param sessionId 조회할 세션의 PK
     * @return 조회된 Session 엔티티
     * @throws CustomException SESSION_NOT_FOUND - 세션을 찾을 수 없음
     */
    @Transactional(readOnly = true)
    public Session getSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
    }

    /**
     * 소유권 검증을 포함한 세션 단건 조회.
     * 요청자가 세션의 참여자(디렉터/촬영자)이거나 관리자(ADMIN)인 경우에만 세션을 반환한다.
     *
     * @param sessionId     조회할 세션의 PK
     * @param requesterId   조회를 요청한 유저 ID
     * @param requesterRole 조회를 요청한 유저의 권한
     * @return 조회된 Session 엔티티
     * @throws CustomException SESSION_NOT_FOUND - 세션을 찾을 수 없음
     * @throws CustomException SESSION_ACCESS_DENIED - 세션 참여자도 관리자도 아님
     */
    @Transactional(readOnly = true)
    public Session getOwnedSession(Long sessionId, Long requesterId, User.Role requesterRole) {
        // 1. 세션 조회 (없으면 예외)
        Session session = getSession(sessionId);

        // 2. 관리자는 운영/디버깅 목적으로 모든 세션 접근 허용
        if (requesterRole == User.Role.ADMIN) {
            return session;
        }

        // 3. 세션 참여자(디렉터 또는 촬영자)가 아니면 접근 거부
        if (!session.isParticipant(requesterId)) {
            throw new CustomException(ErrorCode.SESSION_ACCESS_DENIED);
        }

        return session;
    }
}
