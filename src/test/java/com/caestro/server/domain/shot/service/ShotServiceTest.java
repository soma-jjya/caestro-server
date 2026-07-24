package com.caestro.server.domain.shot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ShotServiceTest {

    @Mock
    private ShotRepository shotRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ShotService shotService;

    private static final Long DIRECTOR_ID = 1L;
    private static final Long SESSION_ID = 10L;

    private User user() {
        return User.builder().oauthProvider("test").oauthId("u").role(User.Role.USER).build();
    }

    private CreateShotRequest request(Long sessionId, String mode, Integer bestCutScore,
                                      Integer width, Integer height, Integer fileSizeKb,
                                      BigDecimal latitude, BigDecimal longitude) {
        return new CreateShotRequest(sessionId, mode, bestCutScore, width, height, fileSizeKb, latitude, longitude, null);
    }

    private Shot capturedSavedShot() {
        ArgumentCaptor<Shot> captor = ArgumentCaptor.forClass(Shot.class);
        org.mockito.Mockito.verify(shotRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("협업 세션이 지정되면 세션의 camera_id를 스냅샷으로 복사해 저장한다")
    void createShot_collabSession_snapshotsCamera() {
        // given
        User director = user();
        User camera = user();
        Session session = Session.builder().id(SESSION_ID).camera(camera).build();
        given(userRepository.findById(DIRECTOR_ID)).willReturn(Optional.of(director));
        given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(shotRepository.save(any(Shot.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        shotService.createShot(DIRECTOR_ID,
                request(SESSION_ID, "COLLAB", 80, 3840, 2160, 1500, new BigDecimal("37.5"), new BigDecimal("127.0")));

        // then
        Shot saved = capturedSavedShot();
        assertThat(saved.getSession()).isEqualTo(session);
        assertThat(saved.getDirector()).isEqualTo(director);
        assertThat(saved.getCamera()).isEqualTo(camera);
        assertThat(saved.getMode()).isEqualTo(ShotMode.COLLAB);
        assertThat(saved.getWidth()).isEqualTo(3840);
        assertThat(saved.getHeight()).isEqualTo(2160);
        assertThat(saved.getFileSizeKb()).isEqualTo(1500);
        assertThat(saved.getLatitude()).isEqualByComparingTo("37.5");
    }

    @Test
    @DisplayName("session_id가 없으면(1인 모드) camera는 NULL, image_url은 NULL, is_cloud_backed는 false로 저장한다")
    void createShot_solo_cameraNullAndDefaults() {
        // given
        User director = user();
        given(userRepository.findById(DIRECTOR_ID)).willReturn(Optional.of(director));
        given(shotRepository.save(any(Shot.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        shotService.createShot(DIRECTOR_ID, request(null, "SOLO", null, null, null, null, null, null));

        // then
        Shot saved = capturedSavedShot();
        assertThat(saved.getSession()).isNull();
        assertThat(saved.getCamera()).isNull();
        assertThat(saved.getDirector()).isEqualTo(director);
        assertThat(saved.getImageUrl()).isNull();
        assertThat(saved.getIsCloudBacked()).isFalse();
        assertThat(saved.getTakenAt()).isNotNull();
        assertThat(saved.getWidth()).isNull();
        assertThat(saved.getLatitude()).isNull();
    }

    @Test
    @DisplayName("라이트 모드 세션(camera 미연결)이면 camera는 NULL로 저장한다")
    void createShot_lightModeSession_cameraNull() {
        // given
        Session session = Session.builder().id(SESSION_ID).camera(null).build();
        given(userRepository.findById(DIRECTOR_ID)).willReturn(Optional.of(user()));
        given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(shotRepository.save(any(Shot.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        shotService.createShot(DIRECTOR_ID, request(SESSION_ID, "COLLAB", null, null, null, null, null, null));

        // then
        assertThat(capturedSavedShot().getCamera()).isNull();
    }

    @Test
    @DisplayName("width/height 중 하나만 전달되면 400(INVALID_SHOT_DIMENSION)")
    void createShot_onlyWidth_throws() {
        assertThatThrownBy(() ->
                shotService.createShot(DIRECTOR_ID, request(null, "SOLO", null, 3840, null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SHOT_DIMENSION);
    }

    @Test
    @DisplayName("latitude/longitude 중 하나만 전달되면 400(INVALID_SHOT_LOCATION)")
    void createShot_onlyLatitude_throws() {
        assertThatThrownBy(() ->
                shotService.createShot(DIRECTOR_ID, request(null, "SOLO", null, null, null, null, new BigDecimal("37.5"), null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SHOT_LOCATION);
    }

    @Test
    @DisplayName("mode가 유효하지 않으면 400(INVALID_SHOT_MODE)")
    void createShot_invalidMode_throws() {
        assertThatThrownBy(() ->
                shotService.createShot(DIRECTOR_ID, request(null, "INVALID", null, null, null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SHOT_MODE);
    }

    @Test
    @DisplayName("mode가 null이면 400(INVALID_SHOT_MODE)")
    void createShot_nullMode_throws() {
        assertThatThrownBy(() ->
                shotService.createShot(DIRECTOR_ID, request(null, null, null, null, null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SHOT_MODE);
    }

    @Test
    @DisplayName("COLLAB 모드인데 session_id가 없으면 400(COLLAB_REQUIRES_SESSION)")
    void createShot_collabWithoutSession_throws() {
        assertThatThrownBy(() ->
                shotService.createShot(DIRECTOR_ID, request(null, "COLLAB", null, null, null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.COLLAB_REQUIRES_SESSION);
    }

    @Test
    @DisplayName("SOLO 모드인데 session_id가 있으면 400(SOLO_MUST_NOT_HAVE_SESSION)")
    void createShot_soloWithSession_throws() {
        assertThatThrownBy(() ->
                shotService.createShot(DIRECTOR_ID, request(SESSION_ID, "SOLO", null, null, null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOLO_MUST_NOT_HAVE_SESSION);
    }

    @Test
    @DisplayName("존재하지 않는 session_id이면 404(SESSION_NOT_FOUND)")
    void createShot_sessionNotFound_throws() {
        given(userRepository.findById(DIRECTOR_ID)).willReturn(Optional.of(user()));
        given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                shotService.createShot(DIRECTOR_ID, request(SESSION_ID, "COLLAB", null, null, null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("내 촬영 이력 조회는 director 기준으로 repository에 위임한다")
    void getMyShots_delegatesToRepository() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Shot> page = new PageImpl<>(List.of(mock(Shot.class)));
        given(shotRepository.findByDirectorId(DIRECTOR_ID, pageable)).willReturn(page);

        assertThat(shotService.getMyShots(DIRECTOR_ID, pageable)).isSameAs(page);
    }

    @Test
    @DisplayName("단건 조회: 본인 소유면 정상 반환한다")
    void getOwnedShot_owner_returns() {
        User director = mock(User.class);
        given(director.getId()).willReturn(DIRECTOR_ID);
        Shot shot = mock(Shot.class);
        given(shot.getDirector()).willReturn(director);
        given(shotRepository.findById(100L)).willReturn(Optional.of(shot));

        assertThat(shotService.getOwnedShot(100L, DIRECTOR_ID)).isEqualTo(shot);
    }

    @Test
    @DisplayName("단건 조회: 타인 소유면 403(SHOT_ACCESS_DENIED)")
    void getOwnedShot_notOwner_throws() {
        User director = mock(User.class);
        given(director.getId()).willReturn(DIRECTOR_ID);
        Shot shot = mock(Shot.class);
        given(shot.getDirector()).willReturn(director);
        given(shotRepository.findById(100L)).willReturn(Optional.of(shot));

        assertThatThrownBy(() -> shotService.getOwnedShot(100L, 999L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SHOT_ACCESS_DENIED);
    }

    @Test
    @DisplayName("단건 조회: 존재하지 않는 id이면 404(SHOT_NOT_FOUND)")
    void getOwnedShot_notFound_throws() {
        given(shotRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> shotService.getOwnedShot(999L, DIRECTOR_ID))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SHOT_NOT_FOUND);
    }
}
