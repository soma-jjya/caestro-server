package com.caestro.server.domain.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.domain.catalog.dto.LocalizedText;
import com.caestro.server.domain.catalog.dto.request.UpsertBuiltinFrameRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogBuiltinFrameResponse;
import com.caestro.server.domain.catalog.entity.CatalogBuiltinFrame;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.repository.CatalogBuiltinFrameRepository;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogBuiltinServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
    private static final List<FrameWindowDto> WINDOWS = List.of(
            new FrameWindowDto(0.05, 0.02, 0.43, 0.38, 0), new FrameWindowDto(0.51, 0.02, 0.43, 0.38, 0),
            new FrameWindowDto(0.05, 0.43, 0.43, 0.41, 0), new FrameWindowDto(0.51, 0.43, 0.43, 0.41, 0));

    @Mock private CatalogBuiltinFrameRepository repository;
    @Mock private CatalogImageRepository imageRepository;
    @Mock private CatalogService catalogService;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private CatalogBuiltinService service;

    @BeforeEach
    void setUp() {
        service = new CatalogBuiltinService(repository, imageRepository, catalogService, new ObjectMapper(), clock);
        lenient().when(repository.save(any(CatalogBuiltinFrame.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(catalogService.toInstant(any())).thenAnswer(inv -> {
            LocalDateTime t = inv.getArgument(0);
            return t == null ? null : t.toInstant(ZoneOffset.UTC);
        });
        lenient().when(catalogService.imageUrl(any())).thenAnswer(inv -> "https://api.peakpic.app/catalog/images/" + inv.getArgument(0));
    }

    private static UpsertBuiltinFrameRequest req(LocalizedText title, Boolean pro, LocalizedText badge, Boolean hidden) {
        return new UpsertBuiltinFrameRequest(title, null, pro, badge, hidden, null, null, null);
    }

    @Test
    @DisplayName("처음 덮어쓰면 행을 만들고, 보낸 것만 담는다(나머지는 null = 앱 기본값)")
    void firstUpdateCreatesRow() {
        given(repository.findById("gyaru")).willReturn(Optional.empty());

        CatalogBuiltinFrameResponse res = service.update("gyaru", req(new LocalizedText("갸루 야호", "Gyaru"), true, null, null));

        assertThat(res.kind()).isEqualTo("art");
        assertThat(res.title()).isEqualTo(new LocalizedText("갸루 야호", "Gyaru"));
        assertThat(res.subtitle()).isNull();
        assertThat(res.pro()).isTrue();
        assertThat(res.badge()).isNull();
        assertThat(res.hidden()).isFalse();
        assertThat(res.imageUrl()).isNull();
    }

    @Test
    @DisplayName("한국어를 비워 보낸 이름·배지는 앱 기본값으로 돌아간다")
    void blankTextResets() {
        CatalogBuiltinFrame row = CatalogBuiltinFrame.builder().id("Black").titleKo("까망").titleEn("Dark").badgeKo("인기").badgeEn("Hot")
                .updatedAt(LocalDateTime.now(clock)).build();
        given(repository.findById("Black")).willReturn(Optional.of(row));

        CatalogBuiltinFrameResponse res = service.update("Black", req(new LocalizedText("", null), null, new LocalizedText(" ", null), null));

        assertThat(res.kind()).isEqualTo("base");
        assertThat(res.title()).isNull();
        assertThat(res.badge()).isNull();
    }

    @Test
    @DisplayName("AI 프레임은 그림과 창을 함께 바꾸고, 창만 고치거나 원래 그림으로 돌릴 수 있다")
    void artworkForArtFrames() {
        CatalogImage image = CatalogImage.builder().id("img000000000000000000A").contentType("image/png")
                .sizeBytes(1).width(1500).height(2000).createdAt(LocalDateTime.now(clock)).build();
        given(repository.findById("street")).willReturn(Optional.empty());
        given(imageRepository.findById(image.getId())).willReturn(Optional.of(image));

        CatalogBuiltinFrameResponse res = service.update("street",
                new UpsertBuiltinFrameRequest(null, null, null, null, null, image.getId(), WINDOWS, null));

        assertThat(res.imageUrl()).endsWith(image.getId());
        assertThat(res.width()).isEqualTo(1500);
        assertThat(res.windows()).hasSize(4);
    }

    @Test
    @DisplayName("단색 프레임에 그림, 새 그림에 창 없음, 바꾼 그림 없이 창만, 모르는 id 는 거절")
    void artworkValidation() {
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("White", new UpsertBuiltinFrameRequest(null, null, null, null, null, "img", WINDOWS, null)))
                .extracting("errorCode").isEqualTo(ErrorCode.CATALOG_INVALID_FRAME);
        assertThatThrownBy(() -> service.update("gyaru", new UpsertBuiltinFrameRequest(null, null, null, null, null, "img", null, null)))
                .extracting("errorCode").isEqualTo(ErrorCode.CATALOG_INVALID_FRAME);
        assertThatThrownBy(() -> service.update("gyaru", new UpsertBuiltinFrameRequest(null, null, null, null, null, null, WINDOWS, null)))
                .extracting("errorCode").isEqualTo(ErrorCode.CATALOG_INVALID_FRAME);
        assertThatThrownBy(() -> service.update("melody", req(null, true, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CATALOG_BUILTIN_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제는 휴지통(앱에서 숨김), 되살리면 다시 보인다. 휴지통에 없는 것을 되살리면 404")
    void trashAndRestore() {
        CatalogBuiltinFrame row = CatalogBuiltinFrame.builder().id("vintage").updatedAt(LocalDateTime.now(clock)).build();
        given(repository.findById("vintage")).willReturn(Optional.of(row));

        service.delete("vintage");
        assertThat(row.isTrashed()).isTrue();
        assertThat(service.toResponse(row).hidden()).isTrue();

        CatalogBuiltinFrameResponse restored = service.restore("vintage");
        assertThat(restored.hidden()).isFalse();
        assertThat(restored.deletedAt()).isNull();

        assertThatThrownBy(() -> service.restore("vintage")).extracting("errorCode").isEqualTo(ErrorCode.CATALOG_NOT_IN_TRASH);
    }

    @Test
    @DisplayName("원래대로는 덮어쓴 행을 지운다(없으면 아무것도 안 함)")
    void reset() {
        CatalogBuiltinFrame row = CatalogBuiltinFrame.builder().id("picnic").updatedAt(LocalDateTime.now(clock)).build();
        given(repository.findById("picnic")).willReturn(Optional.of(row));
        given(repository.findById("chrome")).willReturn(Optional.empty());

        service.reset("picnic");
        service.reset("chrome");

        verify(repository).delete(row);
        verify(repository, never()).delete(org.mockito.ArgumentMatchers.argThat(r -> r != null && "chrome".equals(r.getId())));
    }
}
