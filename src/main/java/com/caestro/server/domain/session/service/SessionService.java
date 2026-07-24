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

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    /**
     * 디렉터가 생성한 세션을 DB에 영속화한다.
     * 시그널링 서버에서 Redis에 세션을 저장한 뒤, 영구 기록을 위해 호출된다.
     *
     * @param sessionCode    발급된 세션 코드
     * @param directorUserId 세션을 생성한 디렉터의 유저 ID
     * @param expiresAt      세션 만료 시각
     * @param liteToken      라이트 모드(비로그인 촬영자) 참여 토큰
     * @return 저장된 Session 엔티티
     * @throws CustomException USER_NOT_FOUND - 디렉터 유저를 찾을 수 없음
     */
    @Transactional
    public Session createSession(String sessionCode, Long directorUserId, LocalDateTime expiresAt, String liteToken) {
        // 1. 디렉터 유저 조회 (없으면 예외)
        User director = userRepository.findById(directorUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 2. 세션 엔티티 생성 (초기 상태: WAITING, 기본 카메라 모드: APP)
        Session session = Session.builder()
                .sessionCode(sessionCode)
                .director(director)
                .cameraMode("APP")
                .status("WAITING")
                .liteToken(liteToken)
                .expiresAt(expiresAt)
                .build();

        // 3. 세션 저장 후 반환
        return sessionRepository.save(session);
    }

    /**
     * 촬영자가 세션에 입장했을 때 세션의 연결 정보를 갱신한다.
     * 시그널링 서버에서 Redis 상태를 갱신한 뒤 DB 상태를 동기화하기 위해 호출된다.
     *
     * @param sessionCode  입장할 세션 코드
     * @param cameraUserId 촬영자 유저 ID (라이트 모드인 경우 null)
     * @param cameraMode   카메라 모드 (APP / LIGHT_MODE)
     * @throws CustomException SESSION_NOT_FOUND - 세션을 찾을 수 없음
     * @throws CustomException USER_NOT_FOUND - 촬영자 유저를 찾을 수 없음
     */
    @Transactional
    public void joinSession(String sessionCode, Long cameraUserId, String cameraMode) {
        // 1. 세션 코드로 세션 조회 (없으면 예외)
        Session session = sessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));

        // 2. 촬영자 유저 조회 (라이트 모드면 유저 없이 null 유지)
        User camera = null;
        if (cameraUserId != null) {
            camera = userRepository.findById(cameraUserId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        }

        // 3. 세션을 연결됨 상태로 전환 (변경 감지로 자동 반영)
        session.connect(camera, cameraMode);
    }

    /**
     * 세션을 종료 상태로 전환한다.
     * 연결 종료(disconnect) 등으로 세션이 끝났을 때 호출되며,
     * 세션이 존재하지 않으면 예외 없이 조용히 넘어간다.
     *
     * @param sessionCode 종료할 세션 코드
     */
    @Transactional
    public void endSession(String sessionCode) {
        // 1. 세션 코드로 세션 조회 후, 존재하는 경우에만 종료 처리
        sessionRepository.findBySessionCode(sessionCode)
                .ifPresent(Session::end);
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
