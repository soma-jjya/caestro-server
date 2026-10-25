package com.caestro.server.domain.b2b.service;

import static org.assertj.core.api.Assertions.*;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.global.exception.CustomException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class B2bPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"b2b", "fill", "admin", "auth", "api", "login", "assets", "static", "frames",
            "catalog", "d", "www", "app", "health", "studio"})
    void reservedPathsCannotBecomeProjects(String slug) {
        assertThat(B2bPolicy.slugProblem(slug)).isEqualTo("reserved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "Yuja", "유자", "a/b", "a_b", " yuja", "yuja ",
            "12345678901234567890123456789012345678901"})
    void slugFormatIsStrict(String slug) { assertThat(B2bPolicy.slugProblem(slug)).isEqualTo("format"); }

    @ParameterizedTest
    @ValueSource(strings = {"yuja", "store-2026", "abc", "1234567890123456789012345678901234567890"})
    void validSlugs(String slug) { assertThat(B2bPolicy.slugProblem(slug)).isNull(); }

    @Test
    void windowsAcceptIncompleteDraftButRejectNonFiniteAndOutOfBounds() {
        assertThat(B2bPolicy.windows(null)).isEmpty();
        assertThat(B2bPolicy.windows(List.of(new FrameWindowDto(0, 0, 1, 1, 180)))).hasSize(1);
        for (FrameWindowDto window : List.of(new FrameWindowDto(Double.NaN, 0, .5, .5, 0),
                new FrameWindowDto(0, 0, Double.POSITIVE_INFINITY, .5, 0),
                new FrameWindowDto(-.1, 0, .5, .5, 0), new FrameWindowDto(.6, 0, .5, .5, 0),
                new FrameWindowDto(0, 0, 0, .5, 0), new FrameWindowDto(0, 0, .5, .5, 181))) {
            assertThatThrownBy(() -> B2bPolicy.windows(List.of(window))).isInstanceOf(CustomException.class);
        }
    }
}
