package com.caestro.server.domain.b2b.service;

import static org.assertj.core.api.Assertions.*;

import com.caestro.server.domain.b2b.entity.B2bFrame.Layout;
import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
        assertThat(B2bPolicy.windows(null, Layout.STRIP)).isEmpty();
        assertThat(B2bPolicy.windows(List.of(new FrameWindowDto(0, 0, 1, 1, 180)), Layout.STRIP)).hasSize(1);
        for (FrameWindowDto window : List.of(new FrameWindowDto(Double.NaN, 0, .5, .5, 0),
                new FrameWindowDto(0, 0, Double.POSITIVE_INFINITY, .5, 0),
                new FrameWindowDto(-.1, 0, .5, .5, 0), new FrameWindowDto(.6, 0, .5, .5, 0),
                new FrameWindowDto(0, 0, 0, .5, 0), new FrameWindowDto(0, 0, .5, .5, 181))) {
            assertThatThrownBy(() -> B2bPolicy.windows(List.of(window), Layout.STRIP)).isInstanceOf(CustomException.class);
        }
    }

    @ParameterizedTest
    @CsvSource({"SINGLE,1", "STRIP,4", "GRID,4"})
    void windowCountIsCappedByTheLayoutsSlotCount(Layout layout, int slots) {
        // #294: 한 컷(SINGLE)은 창 1개, 네컷은 4개까지. 초안은 모자라도 되지만 넘칠 수는 없다
        FrameWindowDto window = new FrameWindowDto(0, 0, .5, .5, 0);
        assertThat(layout.slots()).isEqualTo(slots);
        assertThat(B2bPolicy.windows(List.of(), layout)).isEmpty();
        assertThat(B2bPolicy.windows(Collections.nCopies(slots, window), layout)).hasSize(slots);
        assertThatThrownBy(() -> B2bPolicy.windows(Collections.nCopies(slots + 1, window), layout))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.B2B_INVALID_FRAME));
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void everyLayoutMapsToAFillLayoutWithTheSameSlotCount(Layout layout) {
        // 창 수와 함께 채우기 칸 수가 어긋나면 창이 남거나 칸이 비어 완성되지 않는다
        assertThat(FillService.slotCount(B2bFillService.fillLayout(layout))).isEqualTo(layout.slots());
    }

    @Test
    void singleMapsToTheSingleFillLayout() {
        assertThat(B2bFillService.fillLayout(Layout.SINGLE)).isEqualTo("Single");
        assertThat(B2bFillService.fillLayout(Layout.STRIP)).isEqualTo("Strip");
        assertThat(B2bFillService.fillLayout(Layout.GRID)).isEqualTo("Grid");
    }
}
