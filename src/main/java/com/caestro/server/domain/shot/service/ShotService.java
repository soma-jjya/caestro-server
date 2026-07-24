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
     * 촬영 결과물 메타데이터를 저장한다.
     * director는 인증 토큰의 사용자로 서버에서 지정하고, camera는 세션의 촬영자(camera_id)를 스냅샷으로 복사한다.
     * 실제 이미지 파일은 업로드하지 않으므로 image_url은 항상 null, is_cloud_backed는 false로 저장한다.
     *
     * @param directorId 인증된 사용자(디렉터) ID
     * @param request    촬영 결과물 메타데이터 요청
     * @return 저장된 Shot 엔티티
     * @throws CustomException INVALID_SHOT_MODE / INVALID_SHOT_DIMENSION / INVALID_SHOT_LOCATION - 유효성 위반
     * @throws CustomException USER_NOT_FOUND - 디렉터 유저 없음
     * @throws CustomException SESSION_NOT_FOUND - session_id가 존재하지 않는 세션
     */
    @Transactional
    public Shot createShot(Long directorId, CreateShotRequest request) {
        // 1. 교차 필드/enum 검증 (단일 필드 양수·범위 검증은 DTO Bean Validation에서 선처리됨)
        ShotMode mode = ShotMode.from(request.mode())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_SHOT_MODE));
        // 모드-세션 정합성 검증
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

        // 2. 디렉터 조회 (인증된 사용자이므로 존재해야 함)
        User director = userRepository.findById(directorId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 3. 세션이 지정된 경우 조회 후 camera_id를 스냅샷으로 복사 (라이트 모드/미연결이면 null)
        Session session = null;
        User camera = null;
        if (request.sessionId() != null) {
            session = sessionRepository.findById(request.sessionId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
            camera = session.getCamera();
        }

        // 4. 저장 (image_url은 항상 null, is_cloud_backed는 false, taken_at은 요청값 없으면 현재 시각)
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
     * 인증된 사용자(디렉터) 본인의 촬영 이력을 페이지 단위로 조회한다.
     *
     * @param directorId 인증된 사용자 ID
     * @param pageable   페이지네이션/정렬 정보 (기본 정렬은 taken_at DESC)
     * @return 본인 촬영 결과물 페이지
     */
    @Transactional(readOnly = true)
    public Page<Shot> getMyShots(Long directorId, Pageable pageable) {
        return shotRepository.findByDirectorId(directorId, pageable);
    }

    /**
     * 촬영 결과물 단건을 소유권 검증과 함께 조회한다.
     * 요청자가 해당 결과물의 디렉터가 아니면 접근을 차단한다.
     *
     * @param shotId      조회할 결과물 ID
     * @param requesterId 요청한 사용자 ID
     * @return 조회된 Shot 엔티티
     * @throws CustomException SHOT_NOT_FOUND - 결과물 없음
     * @throws CustomException SHOT_ACCESS_DENIED - 본인 소유가 아님
     */
    @Transactional(readOnly = true)
    public Shot getOwnedShot(Long shotId, Long requesterId) {
        // 1. 결과물 조회 (없으면 404)
        Shot shot = shotRepository.findById(shotId)
                .orElseThrow(() -> new CustomException(ErrorCode.SHOT_NOT_FOUND));

        // 2. 소유권 검증 (디렉터 본인이 아니면 403)
        if (!shot.getDirector().getId().equals(requesterId)) {
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
