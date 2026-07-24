package com.caestro.server.domain.devicespec.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.devicespec.entity.DeviceSpec;
import com.caestro.server.domain.devicespec.enums.DeviceRole;
import com.caestro.server.domain.devicespec.repository.DeviceSpecRepository;
import com.caestro.server.domain.session.entity.Session;
import com.caestro.server.domain.session.repository.SessionRepository;
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

@ExtendWith(MockitoExtension.class)
class DeviceSpecServiceTest {

    @Mock
    private DeviceSpecRepository deviceSpecRepository;

    @Mock
    private SessionRepository sessionRepository;

    @InjectMocks
    private DeviceSpecService deviceSpecService;

    private static final String SESSION_CODE = "a1b2c3d4";
    private static final Long SESSION_ID = 1L;

    private Session session() {
        return Session.builder().id(SESSION_ID).sessionCode(SESSION_CODE).build();
    }

    @Test
    @DisplayName("기존 스펙이 없으면 신규 저장(insert)된다")
    void saveDeviceSpec_insert_whenNotExists() {
        // given
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.of(session()));
        given(deviceSpecRepository.findBySessionIdAndRole(SESSION_ID, DeviceRole.CAMERA)).willReturn(Optional.empty());

        // when
        deviceSpecService.saveDeviceSpec(SESSION_CODE, "CAMERA",
                new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("1.7778"),
                "3840x2160", "AOS");

        // then
        ArgumentCaptor<DeviceSpec> captor = ArgumentCaptor.forClass(DeviceSpec.class);
        verify(deviceSpecRepository, times(1)).save(captor.capture());
        DeviceSpec saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo(DeviceRole.CAMERA);
        assertThat(saved.getMaxZoom()).isEqualByComparingTo("10.00");
        assertThat(saved.getMaxResolution()).isEqualTo("3840x2160");
        assertThat(saved.getOsType()).isEqualTo("AOS");
    }

    @Test
    @DisplayName("기존 스펙이 있으면 새 row를 추가하지 않고 값을 갱신(update)한다")
    void saveDeviceSpec_update_whenExists() {
        // given
        DeviceSpec existing = DeviceSpec.builder()
                .session(session()).role(DeviceRole.CAMERA)
                .maxZoom(new BigDecimal("5.00")).maxResolution("1920x1080").osType("AOS")
                .build();
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.of(session()));
        given(deviceSpecRepository.findBySessionIdAndRole(SESSION_ID, DeviceRole.CAMERA)).willReturn(Optional.of(existing));

        // when
        deviceSpecService.saveDeviceSpec(SESSION_CODE, "CAMERA",
                new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("1.7778"),
                "3840x2160", "iOS");

        // then: save는 호출되지 않고 기존 엔티티가 갱신된다 (변경 감지)
        verify(deviceSpecRepository, never()).save(any());
        assertThat(existing.getMaxZoom()).isEqualByComparingTo("10.00");
        assertThat(existing.getMaxResolution()).isEqualTo("3840x2160");
        assertThat(existing.getOsType()).isEqualTo("iOS");
    }

    @Test
    @DisplayName("존재하지 않는 세션 코드로 수신 시 저장하지 않는다")
    void saveDeviceSpec_sessionNotFound_skipsSave() {
        // given
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.empty());

        // when
        deviceSpecService.saveDeviceSpec(SESSION_CODE, "CAMERA",
                new BigDecimal("10.00"), null, null, null, "AOS");

        // then
        verify(deviceSpecRepository, never()).save(any());
    }

    @Test
    @DisplayName("role이 DIRECTOR/CAMERA가 아니면 저장하지 않는다")
    void saveDeviceSpec_invalidRole_skipsSave() {
        // given
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.of(session()));

        // when
        deviceSpecService.saveDeviceSpec(SESSION_CODE, "GUEST",
                new BigDecimal("10.00"), null, null, null, "iOS");

        // then
        verify(deviceSpecRepository, never()).save(any());
    }

    @Test
    @DisplayName("role이 null이면 저장하지 않는다")
    void saveDeviceSpec_nullRole_skipsSave() {
        // given
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.of(session()));

        // when
        deviceSpecService.saveDeviceSpec(SESSION_CODE, null,
                new BigDecimal("10.00"), null, null, null, "iOS");

        // then
        verify(deviceSpecRepository, never()).save(any());
    }

    @Test
    @DisplayName("동일 세션에 DIRECTOR/CAMERA 스펙을 각각 최초 저장하면 별도 row로 두 번 insert된다")
    void saveDeviceSpec_directorAndCamera_savedSeparately() {
        // given
        given(sessionRepository.findBySessionCode(SESSION_CODE)).willReturn(Optional.of(session()));
        given(deviceSpecRepository.findBySessionIdAndRole(SESSION_ID, DeviceRole.DIRECTOR)).willReturn(Optional.empty());
        given(deviceSpecRepository.findBySessionIdAndRole(SESSION_ID, DeviceRole.CAMERA)).willReturn(Optional.empty());

        // when
        deviceSpecService.saveDeviceSpec(SESSION_CODE, "DIRECTOR",
                null, null, new BigDecimal("2.1667"), "2778x1284", "iOS");
        deviceSpecService.saveDeviceSpec(SESSION_CODE, "CAMERA",
                new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("1.7778"), "3840x2160", "AOS");

        // then
        verify(deviceSpecRepository, times(2)).save(any(DeviceSpec.class));
    }

    @Test
    @DisplayName("session_id 기준 조회 시 저장된 기기 스펙 목록을 반환한다")
    void getDeviceSpecs_returnsList() {
        // given
        DeviceSpec director = DeviceSpec.builder().role(DeviceRole.DIRECTOR).build();
        DeviceSpec camera = DeviceSpec.builder().role(DeviceRole.CAMERA).build();
        given(deviceSpecRepository.findBySessionId(SESSION_ID)).willReturn(List.of(director, camera));

        // when
        List<DeviceSpec> result = deviceSpecService.getDeviceSpecs(SESSION_ID);

        // then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(DeviceSpec::getRole)
                .containsExactly(DeviceRole.DIRECTOR, DeviceRole.CAMERA);
    }
}
