package com.caestro.server.domain.auth.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * nickname 검증 (#134) — 서버가 진위를 확인할 수 없는 클라이언트 주장 값이므로
 * 사용자 입력으로 취급해 길이를 제한한다 (미제한 시 DB varchar 초과 → 500).
 */
class SocialTokenLoginRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("50자 초과 nickname은 검증에 걸린다 (@Valid → 400)")
    void nicknameOver50_isRejected() {
        var request = new SocialTokenLoginRequest("token", null, "가".repeat(51));

        assertThat(validator.validate(request))
                .anyMatch(v -> v.getPropertyPath().toString().equals("nickname"));
    }

    @Test
    @DisplayName("nickname 미전송(null)·50자 이하는 통과한다 — 선택 필드")
    void nicknameOptional_andWithinLimit_pass() {
        assertThat(validator.validate(new SocialTokenLoginRequest("token", null, null))).isEmpty();
        assertThat(validator.validate(new SocialTokenLoginRequest("token", "code", "가".repeat(50)))).isEmpty();
    }
}
