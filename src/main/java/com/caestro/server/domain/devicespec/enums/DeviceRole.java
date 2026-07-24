package com.caestro.server.domain.devicespec.enums;

import java.util.Optional;

public enum DeviceRole {

    DIRECTOR, CAMERA;

    /**
     * 외부에서 수신한 문자열을 DeviceRole로 변환한다.
     * 값이 null이거나 정의되지 않은 역할이면 빈 Optional을 반환한다 (예외를 던지지 않음).
     *
     * @param value 변환할 역할 문자열
     * @return 매칭되는 DeviceRole (없으면 Optional.empty())
     */
    public static Optional<DeviceRole> from(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(DeviceRole.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
