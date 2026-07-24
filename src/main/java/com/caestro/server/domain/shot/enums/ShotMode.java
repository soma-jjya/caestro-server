package com.caestro.server.domain.shot.enums;

import java.util.Optional;

public enum ShotMode {

    SOLO, COLLAB;

    /**
     * 외부에서 수신한 문자열을 ShotMode로 변환한다.
     * 값이 null이거나 정의되지 않은 모드이면 빈 Optional을 반환한다 (예외를 던지지 않음).
     *
     * @param value 변환할 촬영 모드 문자열
     * @return 매칭되는 ShotMode (없으면 Optional.empty())
     */
    public static Optional<ShotMode> from(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ShotMode.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
