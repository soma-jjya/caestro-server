package com.caestro.server.domain.shot.service;

import com.caestro.server.domain.session.entity.Session;
import com.caestro.server.domain.session.repository.SessionRepository;
import com.caestro.server.domain.shot.dto.request.CreateShotRequest;
import com.caestro.server.domain.shot.entity.Shot;
import com.caestro.server.domain.shot.enums.ShotMode;
import com.caestro.server.domain.shot.repository.ShotRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShotService {

    private final ShotRepository shotRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    /**
     * 촬영 결과물 메타데이터를 저장한다. (사진 1장당 레코드 1개 — 한쪽이 저장, 두 참여자 공동 소유)
     * 협업(COLLAB)에서 이 컷의 director는 요청의 directorUserId(없으면 요청자)로 정하고 camera는 세션의 반대편으로
     * 귀속하며, 두 참여자가 세션 참여자인지 검증한다. SOLO는 요청자가 director, camera는 null이다.
     * 실제 이미지 파일은 업로드하지 않으므로 image_url은 항상 null, is_cloud_backed는 false로 저장한다.
     *
     * @param requesterId 저장을 호출한 인증 사용자 ID
     * @param request     촬영 결과물 메타데이터 요청 (directorUserId 포함)
     * @return 저장된 Shot 엔티티
     * @throws CustomException INVALID_SHOT_MODE / INVALID_SHOT_DIMENSION / INVALID_SHOT_LOCATION - 유효성 위반
     * @throws CustomException USER_NOT_FOUND - 요청자 유저 없음
     * @throws CustomException SESSION_NOT_FOUND - session_id가 존재하지 않는 세션
     * @throws CustomException SESSION_ACCESS_DENIED - 요청자/directorUserId가 세션 참여자가 아님
     */
    @Transactional
    public Shot createShot(Long requesterId, CreateShotRequest request) {
        ShotMode mode = ShotMode.from(request.mode())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_SHOT_MODE));

        // 협업(COLLAB)은 두 기기가 세션으로 연결된 상태이므로 session_id가 필수
        if (mode == ShotMode.COLLAB && request.sessionId() == null) {
            throw new CustomException(ErrorCode.COLLAB_REQUIRES_SESSION);
        }
        // 1인(SOLO)은 세션이 없는 상태이므로 session_id가 있으면 안 됨
        if (mode == ShotMode.SOLO && request.sessionId() != null) {
            throw new CustomException(ErrorCode.SOLO_MUST_NOT_HAVE_SESSION);
        }
        validateDimensions(request.width(), request.height());
        validateLocation(request.latitude(), request.longitude());

        // 2. 요청자(저장을 호출한 인증 사용자) 조회
        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 3. 역할 귀속
        //    - SOLO: 요청자가 director, camera는 null
        //    - COLLAB: 요청자가 세션 참여자여야 하고, 이 컷의 director는 요청 바디의 directorUserId(없으면 요청자).
        //      director/camera를 세션의 두 참여자(owner/participant)로 귀속하며, director가 참여자인지 검증한다.
        Session session = null;
        User director = requester;
        User camera = null;
        if (request.sessionId() != null) {
            session = sessionRepository.findById(request.sessionId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));

            // 저장 요청자는 세션 참여자여야 한다
            if (!session.isParticipant(requesterId)) {
                throw new CustomException(ErrorCode.SESSION_ACCESS_DENIED);
            }

            // 이 컷의 디렉터: 클라이언트 명시값(없으면 요청자). 세션 참여자여야 한다.
            Long directorId = request.directorUserId() != null ? request.directorUserId() : requesterId;
            User owner = session.getOwner();
            User participant = session.getParticipant();
            boolean directorIsOwner = owner != null && owner.getId().equals(directorId);
            boolean directorIsParticipant = participant != null && participant.getId().equals(directorId);
            if (!directorIsOwner && !directorIsParticipant) {
                throw new CustomException(ErrorCode.SESSION_ACCESS_DENIED);
            }
            director = directorIsOwner ? owner : participant;
            camera = directorIsOwner ? participant : owner;
        }

        // 저장 (image_url은 항상 null, is_cloud_backed는 false, taken_at은 요청값 없으면 현재 시각)
        Shot shot = Shot.builder()
                .session(session)
                .director(director)
                .camera(camera)
                .mode(mode)
                .bestCutScore(request.bestCutScore())
                .imageUrl(null)
                .width(request.width())
                .height(request.height())
                .fileSizeKb(request.fileSizeKb())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .isCloudBacked(false)
                .takenAt(request.takenAt() != null ? request.takenAt() : LocalDateTime.now())
                .build();

        return shotRepository.save(shot);
    }

    /**
     * 인증된 사용자가 참여한 촬영 이력을 페이지 단위로 조회한다.
     * 협업 결과물은 공동 소유이므로 director 또는 camera가 본인인 컷을 모두 반환한다.
     *
     * @param userId   인증된 사용자 ID
     * @param pageable 페이지네이션/정렬 정보 (기본 정렬은 taken_at DESC)
     * @return 본인이 참여한 촬영 결과물 페이지
     */
    @Transactional(readOnly = true)
    public Page<Shot> getMyShots(Long userId, Pageable pageable) {
        return shotRepository.findByDirectorIdOrCameraId(userId, userId, pageable);
    }

    /**
     * 촬영 결과물 단건을 소유권 검증과 함께 조회한다.
     * 협업 결과물은 공동 소유이므로 director 또는 camera(두 참여자) 본인이면 접근을 허용한다.
     *
     * @param shotId      조회할 결과물 ID
     * @param requesterId 요청한 사용자 ID
     * @return 조회된 Shot 엔티티
     * @throws CustomException SHOT_NOT_FOUND - 결과물 없음
     * @throws CustomException SHOT_ACCESS_DENIED - 참여자 본인이 아님
     */
    @Transactional(readOnly = true)
    public Shot getOwnedShot(Long shotId, Long requesterId) {
        // 1. 결과물 조회 (없으면 404)
        Shot shot = shotRepository.findById(shotId)
                .orElseThrow(() -> new CustomException(ErrorCode.SHOT_NOT_FOUND));

        // 2. 소유권 검증 (director 또는 camera 본인이 아니면 403)
        boolean isDirector = shot.getDirector() != null && shot.getDirector().getId().equals(requesterId);
        boolean isCamera = shot.getCamera() != null && shot.getCamera().getId().equals(requesterId);
        if (!isDirector && !isCamera) {
            throw new CustomException(ErrorCode.SHOT_ACCESS_DENIED);
        }

        return shot;
    }

    /**
     * 해상도(width, height) 교차 필드 검증.
     * 둘 다 있거나 둘 다 없어야 한다. (양수 여부는 DTO의 @Positive에서 검증)
     */
    private void validateDimensions(Integer width, Integer height) {
        if ((width == null) != (height == null)) {
            throw new CustomException(ErrorCode.INVALID_SHOT_DIMENSION);
        }
    }

    /**
     * 위치 정보(latitude, longitude) 교차 필드 검증.
     * 둘 다 있거나 둘 다 없어야 한다. (범위 여부는 DTO의 @DecimalMin/@DecimalMax에서 검증)
     */
    private void validateLocation(BigDecimal latitude, BigDecimal longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new CustomException(ErrorCode.INVALID_SHOT_LOCATION);
        }
    }
}
