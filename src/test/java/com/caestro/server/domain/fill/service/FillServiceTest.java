package com.caestro.server.domain.fill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.fill.dto.FillCustomFrameDto;
import com.caestro.server.domain.fill.dto.FillLayerDto;
import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.dto.request.UpdateFrameRequest;
import com.caestro.server.domain.fill.dto.FillCustomFrameDto;
import com.caestro.server.domain.fill.dto.FillLayerDto;
import com.caestro.server.domain.fill.dto.response.FillResponse;
import com.caestro.server.domain.fill.entity.FillImage;
import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.entity.FillSlot;
import com.caestro.server.domain.fill.repository.FillImageRepository;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.fill.repository.FillSlotRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 함께 채우기 규칙: 칸은 먼저 채운 사람 것, 비우기는 본인 또는 주인, 없애기는 주인만, 만료 세션은 410.
 * 저장소는 모킹하고 엔티티 상태 전이를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class FillServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Mock private FillSessionRepository sessionRepository;
    @Mock private FillSlotRepository slotRepository;
    @Mock private FillImageRepository imageRepository;
    @Mock private UserRepository userRepository;
    @Mock private FillCodeGenerator codeGenerator;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-26T03:00:00Z"), ZoneOffset.UTC);
    private FillService service;
    private User owner;
    private User friend;
    private FillSession session;

    @BeforeEach
    void setUp() {
        service = new FillService(sessionRepository, slotRepository, imageRepository, userRepository,
                codeGenerator, new ObjectMapper(), clock);
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://api.peakpic.app/");
        ReflectionTestUtils.setField(service, "ttlDays", 7L);
        ReflectionTestUtils.setField(service, "maxImageBytes", 3 * 1024 * 1024);
        owner = User.builder().nickname("주인").role(User.Role.USER).build();
        ReflectionTestUtils.setField(owner, "id", 1L);
        friend = User.builder().nickname(null).role(User.Role.USER).build();
        ReflectionTestUtils.setField(friend, "id", 2L);
        session = FillSession.builder().id(10L).code("K7X2MQ").owner(owner).frame("Black").layout("Strip")
                .expiresAt(LocalDateTime.now(clock).plusDays(7)).createdAt(LocalDateTime.now(clock)).build();
    }

    private FillSlot slot(int index) {
        return FillSlot.builder().id(100L + index).session(session).slotIndex(index).build();
    }

    private List<FillSlot> slots(FillSlot... some) {
        return List.of(some);
    }

    @Test
    @DisplayName("생성: 코드를 발급하고 빈 칸 네 개를 만들며 7일 뒤 만료된다")
    void create_makesFourEmptySlots() {
        given(userRepository.findById(1L)).willReturn(Optional.of(owner));
        given(codeGenerator.next()).willReturn("K7X2MQ");
        given(sessionRepository.existsByCode("K7X2MQ")).willReturn(false);
        given(sessionRepository.save(any(FillSession.class))).willAnswer(inv -> {
            FillSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 10L);
            ReflectionTestUtils.setField(s, "createdAt", LocalDateTime.now(clock));
            return s;
        });
        given(slotRepository.save(any(FillSlot.class))).willAnswer(inv -> inv.getArgument(0));

        FillResponse r = service.create(1L, new CreateFillRequest("Black", "Strip", null, null, " 제주   여행 ", null));

        assertThat(r.code()).isEqualTo("K7X2MQ");
        assertThat(r.ownerMe()).isTrue();
        assertThat(r.title()).isEqualTo("제주 여행");
        assertThat(r.slots()).hasSize(4).allMatch(s -> s.imageUrl() == null);
        assertThat(r.expiresAt()).isEqualTo(Instant.parse("2026-10-03T03:00:00Z"));
        verify(slotRepository, org.mockito.Mockito.times(4)).save(any(FillSlot.class));
    }

    @Test
    @DisplayName("생성: frame/layout 값이 앱의 enum 이름이 아니면 400")
    void create_rejectsUnknownFrame() {
        assertThatThrownBy(() -> service.create(1L, new CreateFillRequest("Pink", "Strip", null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_INVALID_FRAME);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("채우기: 빈 칸에 넣으면 내 칸이 되고 이름이 없으면 닉네임을 쓴다")
    void fill_emptySlotBecomesMine() {
        FillSlot s0 = slot(0);
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 0)).willReturn(Optional.of(s0));
        given(userRepository.findById(1L)).willReturn(Optional.of(owner));
        given(codeGenerator.imageId()).willReturn("img000000000000000001a");
        given(imageRepository.save(any(FillImage.class))).willAnswer(inv -> inv.getArgument(0));
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(s0, slot(1), slot(2), slot(3)));

        FillResponse r = service.fillSlot("k7x2mq", 0, 1L, null, JPEG, "image/jpeg");

        assertThat(r.slots().get(0).imageUrl()).isEqualTo("https://api.peakpic.app/fills/K7X2MQ/images/img000000000000000001a");
        assertThat(r.slots().get(0).filledByMe()).isTrue();
        assertThat(r.slots().get(0).filledByName()).isEqualTo("주인");
        assertThat(s0.isFilledBy(1L)).isTrue();
    }

    @Test
    @DisplayName("채우기: 다른 사람이 먼저 채운 칸은 409")
    void fill_takenSlotIsConflict() {
        FillSlot s0 = slot(0);
        s0.fill(friend, "민지", FillImage.builder().id("x").build(), LocalDateTime.now(clock));
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 0)).willReturn(Optional.of(s0));

        assertThatThrownBy(() -> service.fillSlot("K7X2MQ", 0, 1L, null, JPEG, "image/jpeg"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_SLOT_TAKEN);
        verify(imageRepository, never()).save(any());
    }

    @Test
    @DisplayName("채우기: 이미지가 아닌 바이트는 400, 3MB 초과는 413")
    void fill_rejectsBadBytes() {
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 0)).willReturn(Optional.of(slot(0)));
        given(userRepository.findById(1L)).willReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.fillSlot("K7X2MQ", 0, 1L, null, "hello".getBytes(), "image/jpeg"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_INVALID_IMAGE);
        byte[] huge = new byte[3 * 1024 * 1024 + 1];
        huge[0] = (byte) 0xFF; huge[1] = (byte) 0xD8;
        assertThatThrownBy(() -> service.fillSlot("K7X2MQ", 0, 1L, null, huge, "image/jpeg"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_IMAGE_TOO_LARGE);
    }

    @Test
    @DisplayName("비우기: 남이 채운 칸은 주인만 비울 수 있다")
    void clear_othersSlotOnlyByOwner() {
        FillSlot s0 = slot(0);
        FillImage img = FillImage.builder().id("x").build();
        s0.fill(friend, "민지", img, LocalDateTime.now(clock));
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 0)).willReturn(Optional.of(s0));

        // 제3자(3L)는 403
        assertThatThrownBy(() -> service.clearSlot("K7X2MQ", 0, 3L))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_ACCESS_DENIED);

        // 주인(1L)은 비울 수 있고 이미지는 삭제된다
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(s0, slot(1), slot(2), slot(3)));
        FillResponse r = service.clearSlot("K7X2MQ", 0, 1L);
        assertThat(s0.isEmpty()).isTrue();
        assertThat(r.slots().get(0).imageUrl()).isNull();
        verify(imageRepository).delete(img);
    }

    @Test
    @DisplayName("없애기: 주인만 가능하고 칸·이미지·세션이 함께 지워진다")
    void delete_onlyOwnerPurgesEverything() {
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));

        assertThatThrownBy(() -> service.delete("K7X2MQ", 2L))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_ACCESS_DENIED);

        service.delete("K7X2MQ", 1L);
        verify(slotRepository).deleteBySessionId(10L);
        verify(imageRepository).deleteBySessionId(10L);
        verify(sessionRepository).delete(session);
    }

    @Test
    @DisplayName("만료된 세션은 410으로 답한다")
    void expiredSessionIsGone() {
        FillSession old = FillSession.builder().id(11L).code("OLDOLD").owner(owner).frame("Black").layout("Strip")
                .expiresAt(LocalDateTime.now(clock).minusMinutes(1)).build();
        given(sessionRepository.findByCode("OLDOLD")).willReturn(Optional.of(old));

        assertThatThrownBy(() -> service.get("OLDOLD", 1L))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_EXPIRED);
    }

    @Test
    @DisplayName("커스텀 프레임 요소: 주인이 PNG를 올리면 해당 layer의 url이 채워진다")
    void frameAsset_fillsLayerUrl() {
        String json = "{\"base\":\"White\",\"layers\":[{\"cx\":0.5,\"cy\":0.9,\"w\":0.3,\"rot\":0.0,\"url\":null}]}";
        FillSession custom = FillSession.builder().id(12L).code("CUSTOM").owner(owner).frame("White").layout("Strip")
                .customFrameJson(json).expiresAt(LocalDateTime.now(clock).plusDays(7)).createdAt(LocalDateTime.now(clock)).build();
        given(sessionRepository.findByCode("CUSTOM")).willReturn(Optional.of(custom));
        given(codeGenerator.imageId()).willReturn("asset00000000000000001");
        given(imageRepository.save(any(FillImage.class))).willAnswer(inv -> inv.getArgument(0));

        String url = service.uploadFrameAsset("CUSTOM", 0, 1L, PNG, "image/png");

        assertThat(url).isEqualTo("https://api.peakpic.app/fills/CUSTOM/images/asset00000000000000001");
        assertThat(custom.getCustomFrameJson()).contains("\"url\":\"" + url + "\"");
        // 참여자는 올릴 수 없다
        assertThatThrownBy(() -> service.uploadFrameAsset("CUSTOM", 0, 2L, PNG, "image/png"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_ACCESS_DENIED);
    }

    @Test
    @DisplayName("이미지 조회: 세션 코드와 id가 함께 맞아야 한다")
    void image_requiresMatchingSessionAndId() {
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(imageRepository.findByIdAndSessionId(anyString(), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.image("K7X2MQ", "nope"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_IMAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("프레임 바꾸기: 주인은 그림 프레임·무테로 갈아 끼우고, 이전 커스텀 요소 이미지는 지워진다")
    void updateFrame_ownerSwapsFrameAndDropsOldAssets() {
        String oldAssetUrl = "https://api.peakpic.app/fills/K7X2MQ/images/asset01";
        session.updateCustomFrameJson("{\"base\":\"White\",\"layers\":[{\"cx\":0.5,\"cy\":0.9,\"w\":0.3,\"rot\":0,\"url\":\"" + oldAssetUrl + "\"}],\"borderless\":true}");
        FillImage oldAsset = FillImage.builder().id("asset01").session(session).contentType("image/png").data(PNG).sizeBytes(PNG.length).build();
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(imageRepository.findByIdAndSessionId("asset01", 10L)).willReturn(Optional.of(oldAsset));
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(slot(0), slot(1), slot(2), slot(3)));

        FillResponse r = service.updateFrame("K7X2MQ", 1L, new UpdateFrameRequest("White", "Grid", "gyaru", null, null));

        verify(imageRepository).delete(oldAsset);
        assertThat(r.frame()).isEqualTo("White");
        assertThat(r.layout()).isEqualTo("Grid");
        assertThat(r.artFrameId()).isEqualTo("gyaru");
        assertThat(r.customFrame()).isNull();
        assertThat(r.borderless()).isFalse();
    }

    @Test
    @DisplayName("프레임 바꾸기: 커스텀 프레임으로 바꾸면 요소 url은 비워지고 무테는 요청값이 커스텀 값을 덮는다")
    void updateFrame_customFrameLayersWaitForAssets() {
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(slot(0), slot(1), slot(2), slot(3)));
        FillCustomFrameDto custom = new FillCustomFrameDto("Black", List.of(new FillLayerDto(0.5f, 0.5f, 0.3f, 12f, "https://x/leftover")), false);

        FillResponse r = service.updateFrame("K7X2MQ", 1L, new UpdateFrameRequest("Black", "Grid", null, custom, true));

        assertThat(r.borderless()).isTrue();
        assertThat(r.customFrame().borderless()).isTrue();
        assertThat(r.customFrame().layers()).singleElement().satisfies(l -> assertThat(l.url()).isNull());
    }

    @Test
    @DisplayName("프레임 바꾸기: 주인이 아니면 403")
    void updateFrame_rejectsNonOwner() {
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));

        assertThatThrownBy(() -> service.updateFrame("K7X2MQ", 2L, new UpdateFrameRequest("Black", "Strip", null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_ACCESS_DENIED);
    }
}
