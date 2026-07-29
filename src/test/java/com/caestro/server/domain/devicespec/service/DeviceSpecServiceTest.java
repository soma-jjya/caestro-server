package com.caestro.server.domain.devicespec.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.devicespec.entity.DeviceSpec;
import com.caestro.server.domain.devicespec.repository.DeviceSpecRepository;
import com.caestro.server.domain.session.entity.Session;
import com.caestro.server.domain.session.repository.SessionRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DeviceSpecServiceTest {

    @Mock
    private DeviceSpecRepository deviceSpecRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DeviceSpecService deviceSpecService;

    private static final String SESSION_CODE = "a1b2c3d4";
    private static final Long SESSION_ID = 1L;
    private static final Long USER_ID = 100L;

    private Session session() {
        return Session.builder().id(SESSION_ID).sessionCode(SESSION_CODE).build();
    }

    private User user(Long id) {
        User u = User.builder().oauthProvider("t").oauthId("u" + id).role(User.Role.USER).build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    @DisplayName("기존 스펙이 없으면 신규 저장(insert)된다")
    void saveDeviceSpec_insert_whenNotExists() {
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.of(session()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        given(deviceSpecRepository.findBySessionIdAndUserId(SESSION_ID, USER_ID)).willReturn(Optional.empty());

        deviceSpecService.saveDeviceSpec(SESSION_CODE, USER_ID,
                new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("1.7778"),
                "3840x2160", "AOS");

        ArgumentCaptor<DeviceSpec> captor = ArgumentCaptor.forClass(DeviceSpec.class);
        verify(deviceSpecRepository, times(1)).save(captor.capture());
        DeviceSpec saved = captor.getValue();
        assertThat(saved.getUser().getId()).isEqualTo(USER_ID);
        assertThat(saved.getMaxZoom()).isEqualByComparingTo("10.00");
        assertThat(saved.getMaxResolution()).isEqualTo("3840x2160");
        assertThat(saved.getOsType()).isEqualTo("AOS");
    }

    @Test
    @DisplayName("기존 스펙이 있으면 새 row를 추가하지 않고 값을 갱신(update)한다")
    void saveDeviceSpec_update_whenExists() {
        DeviceSpec existing = DeviceSpec.builder()
                .session(session()).user(user(USER_ID))
                .maxZoom(new BigDecimal("5.00")).maxResolution("1920x1080").osType("AOS")
                .build();
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.of(session()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        given(deviceSpecRepository.findBySessionIdAndUserId(SESSION_ID, USER_ID)).willReturn(Optional.of(existing));

        deviceSpecService.saveDeviceSpec(SESSION_CODE, USER_ID,
                new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("1.7778"),
                "3840x2160", "iOS");

        // save는 호출되지 않고 기존 엔티티가 갱신된다 (변경 감지)
        verify(deviceSpecRepository, never()).save(any());
        assertThat(existing.getMaxZoom()).isEqualByComparingTo("10.00");
        assertThat(existing.getMaxResolution()).isEqualTo("3840x2160");
        assertThat(existing.getOsType()).isEqualTo("iOS");
    }

    @Test
    @DisplayName("존재하지 않는 세션 코드로 수신 시 저장하지 않는다")
    void saveDeviceSpec_sessionNotFound_skipsSave() {
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.empty());

        deviceSpecService.saveDeviceSpec(SESSION_CODE, USER_ID,
                new BigDecimal("10.00"), null, null, null, "AOS");

        verify(deviceSpecRepository, never()).save(any());
    }

    @Test
    @DisplayName("userId가 null이면 저장하지 않는다")
    void saveDeviceSpec_nullUser_skipsSave() {
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.of(session()));

        deviceSpecService.saveDeviceSpec(SESSION_CODE, null,
                new BigDecimal("10.00"), null, null, null, "iOS");

        verify(deviceSpecRepository, never()).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 유저면 저장하지 않는다")
    void saveDeviceSpec_userNotFound_skipsSave() {
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.of(session()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        deviceSpecService.saveDeviceSpec(SESSION_CODE, USER_ID,
                new BigDecimal("10.00"), null, null, null, "iOS");

        verify(deviceSpecRepository, never()).save(any());
    }

    @Test
    @DisplayName("동일 세션에 서로 다른 두 유저 스펙을 최초 저장하면 별도 row로 두 번 insert된다")
    void saveDeviceSpec_twoUsers_savedSeparately() {
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.of(session()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        given(userRepository.findById(200L)).willReturn(Optional.of(user(200L)));
        given(deviceSpecRepository.findBySessionIdAndUserId(SESSION_ID, USER_ID)).willReturn(Optional.empty());
        given(deviceSpecRepository.findBySessionIdAndUserId(SESSION_ID, 200L)).willReturn(Optional.empty());

        deviceSpecService.saveDeviceSpec(SESSION_CODE, USER_ID,
                null, null, new BigDecimal("2.1667"), "2778x1284", "iOS");
        deviceSpecService.saveDeviceSpec(SESSION_CODE, 200L,
                new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("1.7778"), "3840x2160", "AOS");

        verify(deviceSpecRepository, times(2)).save(any(DeviceSpec.class));
    }

    @Test
    @DisplayName("session_id 기준 조회 시 저장된 기기 스펙 목록을 반환한다")
    void getDeviceSpecs_returnsList() {
        DeviceSpec a = DeviceSpec.builder().user(user(USER_ID)).build();
        DeviceSpec b = DeviceSpec.builder().user(user(200L)).build();
        given(deviceSpecRepository.findBySessionId(SESSION_ID)).willReturn(List.of(a, b));

        List<DeviceSpec> result = deviceSpecService.getDeviceSpecs(SESSION_ID);

        assertThat(result).hasSize(2);
    }
}
