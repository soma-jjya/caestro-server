package com.caestro.server.domain.shot.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreateShotRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private CreateShotRequest request(Integer width, Integer height, Integer fileSizeKb,
                                      BigDecimal latitude, BigDecimal longitude) {
        return new CreateShotRequest(null, "SOLO", null, width, height, fileSizeKb, latitude, longitude, null);
    }

    @Test
    @DisplayName("width/height/파일용량이 양수, 위치가 범위 내면 위반이 없다")
    void valid_noViolations() {
        var violations = validator.validate(
                request(3840, 2160, 1500, new BigDecimal("37.5"), new BigDecimal("127.0")));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("모든 선택 필드가 null이어도 위반이 없다 (위치 권한 거부 시나리오 포함)")
    void allNull_noViolations() {
        var violations = validator.validate(request(null, null, null, null, null));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("width가 0 이하이면 @Positive 위반이 발생한다")
    void nonPositiveWidth_violation() {
        var violations = validator.validate(request(0, 1080, null, null, null));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("width"));
    }

    @Test
    @DisplayName("fileSizeKb가 음수이면 @Positive 위반이 발생한다")
    void negativeFileSize_violation() {
        var violations = validator.validate(request(null, null, -5, null, null));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("fileSizeKb"));
    }

    @Test
    @DisplayName("latitude가 범위를 벗어나면 @DecimalMax 위반이 발생한다")
    void latitudeOutOfRange_violation() {
        var violations = validator.validate(request(null, null, null, new BigDecimal("100"), new BigDecimal("127")));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("latitude"));
    }

    @Test
    @DisplayName("longitude가 범위를 벗어나면 @DecimalMin 위반이 발생한다")
    void longitudeOutOfRange_violation() {
        Set<?> violations = validator.validate(request(null, null, null, new BigDecimal("0"), new BigDecimal("-200")));
        assertThat(violations).anyMatch(v -> ((jakarta.validation.ConstraintViolation<?>) v)
                .getPropertyPath().toString().equals("longitude"));
    }
}
