package com.caestro.server.domain.fill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.fill.dto.FillCustomFrameDto;
import com.caestro.server.domain.fill.dto.FillLayerDto;
import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.dto.request.UpdateFrameRequest;
import com.caestro.server.domain.fill.dto.FillCustomFrameDto;
import com.caestro.server.domain.fill.dto.FillLayerDto;
import com.caestro.server.domain.fill.dto.response.FillResponse;
import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.entity.FillSlot;
import com.caestro.server.domain.fill.entity.FillQueueItem;
import com.caestro.server.domain.fill.repository.FillQueueRepository;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.fill.repository.FillSlotRepository;
import com.caestro.server.domain.fill.store.FillImageStore;
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
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0};

    @Mock private FillSessionRepository sessionRepository;
    @Mock private FillSlotRepository slotRepository;
    @Mock private FillQueueRepository queueRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private FillImageStore imageStore;
    @Mock private UserRepository userRepository;
    @Mock private FillCodeGenerator codeGenerator;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-26T03:00:00Z"), ZoneOffset.UTC);
    private FillService service;
    private User owner;
    private User friend;
    private FillSession session;

    @BeforeEach
    void setUp() {
        service = new FillService(sessionRepository, slotRepository, queueRepository, imageStore, userRepository,
                codeGenerator, new ObjectMapper(), clock, eventPublisher);
        // 대기열 저장은 넘긴 것을 그대로 돌려주고, 조회는 비어 있다(각 테스트가 필요하면 채운다)
        lenient().when(queueRepository.save(any(FillQueueItem.class))).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(service, "queueMax", 12);
        // 주소 생성은 저장소의 몫이 됐다 (#152) — 실제 구현과 같은 형식으로 흉내 낸다
        lenient().when(imageStore.urlOf(anyString(), anyString())).thenAnswer(inv ->
                "https://api.peakpic.app/fills/" + inv.getArgument(0) + "/images/" + inv.getArgument(1));
        ReflectionTestUtils.setField(service, "ttlDays", 7L);
        ReflectionTestUtils.setField(service, "maxImageBytes", 3 * 1024 * 1024);
        ReflectionTestUtils.setField(service, "maxGifBytes", 16 * 1024 * 1024);
        ReflectionTestUtils.setField(service, "maxClipBytes", 6 * 1024 * 1024);
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
        given(imageStore.store(any(), anyString(), any())).willReturn("img000000000000000001a");
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(s0, slot(1), slot(2), slot(3)));

        FillResponse r = service.fillSlot("k7x2mq", 0, 1L, null, JPEG, "image/jpeg");

        assertThat(r.slots().get(0).imageUrl()).isEqualTo("https://api.peakpic.app/fills/K7X2MQ/images/img000000000000000001a");
        assertThat(r.slots().get(0).filledByMe()).isTrue();
        assertThat(r.slots().get(0).filledByName()).isEqualTo("주인");
        assertThat(s0.isFilledBy(1L)).isTrue();
    }

    @Test
    @DisplayName("채우기: 다른 사람이 채운 칸도 바꿀 수 있고, 이전 사진은 지워지지 않고 대기열로 간다")
    void fill_othersSlotIsReplacedAndItsPhotoQueued() {
        FillSlot s0 = slot(0);
        s0.fill(friend, "민지", "imgPrev0000000000000001", LocalDateTime.now(clock).minusHours(1));
        s0.attachClip("clpPrev0000000000000001");
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 0)).willReturn(Optional.of(s0));
        given(userRepository.findById(1L)).willReturn(Optional.of(owner));
        given(imageStore.store(any(), anyString(), any())).willReturn("imgNew00000000000000001");
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(s0, slot(1), slot(2), slot(3)));
        // 저장된 줄이 곧 조회 결과: 응답의 queue가 그것으로 만들어진다
        List<FillQueueItem> saved = new java.util.ArrayList<>();
        given(queueRepository.save(any(FillQueueItem.class))).willAnswer(inv -> {
            FillQueueItem item = inv.getArgument(0);
            ReflectionTestUtils.setField(item, "id", 70L + saved.size());
            saved.add(item);
            return item;
        });
        given(queueRepository.findBySessionIdOrderByIdAsc(10L)).willAnswer(inv -> List.copyOf(saved));

        FillResponse r = service.fillSlot("K7X2MQ", 0, 1L, null, JPEG, "image/jpeg");

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getImageId()).isEqualTo("imgPrev0000000000000001");
        assertThat(saved.get(0).getClipId()).isEqualTo("clpPrev0000000000000001");
        assertThat(saved.get(0).getFromSlot()).isZero();
        assertThat(saved.get(0).getFilledByName()).isEqualTo("민지");
        verify(imageStore, never()).delete(any(), anyString());
        assertThat(s0.isFilledBy(1L)).isTrue();
        assertThat(s0.getClipId()).isNull();
        assertThat(r.slots().get(0).imageUrl()).endsWith("imgNew00000000000000001");
        assertThat(r.queue()).hasSize(1);
        assertThat(r.queue().get(0).imageUrl()).endsWith("imgPrev0000000000000001");
        assertThat(r.queue().get(0).clipUrl()).endsWith("clpPrev0000000000000001");
        assertThat(r.queue().get(0).filledByMe()).isFalse();
        assertThat(r.queue().get(0).fromSlot()).isZero();
    }

    @Test
    @DisplayName("대기열: 지우기는 누구나 할 수 있고 사진·클립 바이트까지 사라진다; 없는 id는 404")
    void queue_anyoneDeletesAndBytesGo() {
        FillQueueItem item = FillQueueItem.builder().id(7L).session(session).fromSlot(2).filledBy(friend).filledByName("민지")
                .imageId("imgQ000000000000000001").clipId("clpQ000000000000000001").queuedAt(LocalDateTime.now(clock)).build();
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(queueRepository.findByIdAndSessionId(7L, 10L)).willReturn(Optional.of(item));
        given(queueRepository.findByIdAndSessionId(8L, 10L)).willReturn(Optional.empty());

        FillResponse r = service.deleteQueued("K7X2MQ", 7L, 3L);

        verify(imageStore).delete(session, "imgQ000000000000000001");
        verify(imageStore).delete(session, "clpQ000000000000000001");
        verify(queueRepository).delete(item);
        assertThat(r.queue()).isEmpty();
        assertThatThrownBy(() -> service.deleteQueued("K7X2MQ", 8L, 3L))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_QUEUE_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("대기열: 되돌리기는 사진을 주인·이름·클립과 함께 칸에 넣고, 그 칸에 있던 사진은 대기열로 내려간다")
    void queue_restoreSwapsWithTheOccupant() {
        FillSlot s1 = slot(1);
        s1.fill(owner, "주인", "imgOcc00000000000000001", LocalDateTime.now(clock));
        FillQueueItem item = FillQueueItem.builder().id(7L).session(session).fromSlot(3).filledBy(friend).filledByName("민지")
                .imageId("imgQ000000000000000001").clipId("clpQ000000000000000001")
                .filledAt(LocalDateTime.now(clock).minusDays(1)).queuedAt(LocalDateTime.now(clock)).build();
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 1)).willReturn(Optional.of(s1));
        given(queueRepository.findByIdAndSessionId(7L, 10L)).willReturn(Optional.of(item));
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(slot(0), s1, slot(2), slot(3)));
        ArgumentCaptor<FillQueueItem> queued = ArgumentCaptor.forClass(FillQueueItem.class);

        FillResponse r = service.restoreQueued("K7X2MQ", 7L, 1, 2L);

        verify(queueRepository).save(queued.capture());
        assertThat(queued.getValue().getImageId()).isEqualTo("imgOcc00000000000000001");
        assertThat(queued.getValue().getFromSlot()).isEqualTo(1);
        verify(queueRepository).delete(item);
        verify(imageStore, never()).delete(any(), anyString());
        assertThat(s1.getImageId()).isEqualTo("imgQ000000000000000001");
        assertThat(s1.getClipId()).isEqualTo("clpQ000000000000000001");
        assertThat(s1.isFilledBy(2L)).isTrue();
        assertThat(s1.getFilledByName()).isEqualTo("민지");
        assertThat(r.slots().get(1).filledByMe()).isTrue();
        assertThat(r.slots().get(1).clipUrl()).endsWith("clpQ000000000000000001");
    }

    @Test
    @DisplayName("대기열: 상한을 넘치면 가장 오래 기다린 사진부터 바이트까지 사라진다")
    void queue_oldestDroppedPastTheCap() {
        ReflectionTestUtils.setField(service, "queueMax", 2);
        FillQueueItem oldest = FillQueueItem.builder().id(1L).session(session).fromSlot(0).imageId("imgOld0000000000000001")
                .queuedAt(LocalDateTime.now(clock)).build();
        FillQueueItem older = FillQueueItem.builder().id(2L).session(session).fromSlot(0).imageId("imgOld0000000000000002")
                .queuedAt(LocalDateTime.now(clock)).build();
        FillSlot s0 = slot(0);
        s0.fill(friend, "민지", "imgCur0000000000000001", LocalDateTime.now(clock));
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 0)).willReturn(Optional.of(s0));
        given(queueRepository.findBySessionIdOrderByIdAsc(10L)).willAnswer(inv -> List.of(oldest, older,
                FillQueueItem.builder().id(3L).session(session).fromSlot(0).imageId("imgCur0000000000000001")
                        .queuedAt(LocalDateTime.now(clock)).build()));
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(s0));

        service.clearSlot("K7X2MQ", 0, 3L);

        verify(imageStore).delete(session, "imgOld0000000000000001");
        verify(queueRepository).delete(oldest);
        verify(imageStore, never()).delete(session, "imgOld0000000000000002");
        verify(imageStore, never()).delete(session, "imgCur0000000000000001");
        assertThat(s0.isEmpty()).isTrue();
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
    @DisplayName("비우기: 누구나 비울 수 있고 사진은 지워지지 않고 대기열로 간다; 빈 칸 비우기는 대기열에 아무것도 남기지 않는다")
    void clear_anyoneAndThePhotoIsQueued() {
        FillSlot s0 = slot(0);
        s0.fill(friend, "민지", "x", LocalDateTime.now(clock));
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 0)).willReturn(Optional.of(s0));
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(s0, slot(1), slot(2), slot(3)));

        // 제3자(3L)도 비울 수 있다
        FillResponse r = service.clearSlot("K7X2MQ", 0, 3L);
        assertThat(s0.isEmpty()).isTrue();
        assertThat(r.slots().get(0).imageUrl()).isNull();
        verify(imageStore, never()).delete(any(), anyString());
        verify(queueRepository).save(any(FillQueueItem.class));

        service.clearSlot("K7X2MQ", 0, 1L);
        verify(queueRepository, org.mockito.Mockito.times(1)).save(any(FillQueueItem.class));
    }

    @Test
    @DisplayName("없애기: 주인만 가능하고 칸·이미지·세션이 함께 지워진다")
    void delete_onlyOwnerPurgesEverything() {
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));

        assertThatThrownBy(() -> service.delete("K7X2MQ", 2L))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_ACCESS_DENIED);

        service.delete("K7X2MQ", 1L);
        verify(slotRepository).deleteBySessionId(10L);
        verify(imageStore).deleteBySession(10L);
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
        given(imageStore.store(any(), anyString(), any())).willReturn("asset00000000000000001");

        String url = service.uploadFrameAsset("CUSTOM", 0, 1L, PNG, "image/png");

        assertThat(url).isEqualTo("https://api.peakpic.app/fills/CUSTOM/images/asset00000000000000001");
        assertThat(custom.getCustomFrameJson()).contains("\"url\":\"" + url + "\"");
        // 참여자는 올릴 수 없다
        assertThatThrownBy(() -> service.uploadFrameAsset("CUSTOM", 0, 2L, PNG, "image/png"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_ACCESS_DENIED);
    }

    @Test
    @DisplayName("완성 GIF: 주인이 올리면 gifUrl로 내려가고, 참여자는 올릴 수 없고, JPEG는 GIF로 받지 않는다")
    void gif_ownerUploadsAndOthersCannot() {
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(imageStore.store(any(), anyString(), any())).willReturn("gif000000000000000001");
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(List.of());

        String url = service.uploadGif("K7X2MQ", 1L, GIF, "image/gif");

        assertThat(url).isEqualTo("https://api.peakpic.app/fills/K7X2MQ/images/gif000000000000000001");
        assertThat(session.getGifImageId()).isEqualTo("gif000000000000000001");
        assertThat(service.get("K7X2MQ", 2L).gifUrl()).isEqualTo(url);
        assertThatThrownBy(() -> service.uploadGif("K7X2MQ", 2L, GIF, "image/gif"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_ACCESS_DENIED);
        assertThatThrownBy(() -> service.uploadGif("K7X2MQ", 1L, JPEG, "image/gif"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_INVALID_GIF);
    }

    @Test
    @DisplayName("칸 클립: 채운 본인만 붙이고 clipUrl로 내려가며, 빈 칸·남의 칸·GIF 아닌 것은 거절한다")
    void clip_onlyTheFillerAttachesAGif() {
        FillSlot mine = slot(1);
        mine.fill(friend, "민지", "img000000000000000010", LocalDateTime.now(clock));
        FillSlot empty = slot(2);
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 1)).willReturn(Optional.of(mine));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 2)).willReturn(Optional.of(empty));
        given(imageStore.store(any(), eq("image/gif"), any())).willReturn("clp000000000000000011");
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(List.of(mine, empty));

        FillResponse after = service.uploadSlotClip("K7X2MQ", 1, 2L, GIF, "image/gif");

        assertThat(after.slots().get(1).clipUrl())
                .isEqualTo("https://api.peakpic.app/fills/K7X2MQ/images/clp000000000000000011");
        assertThat(after.slots().get(1).imageUrl()).endsWith("img000000000000000010");
        assertThat(after.slots().get(2).clipUrl()).isNull();
        assertThatThrownBy(() -> service.uploadSlotClip("K7X2MQ", 1, 1L, GIF, "image/gif"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_ACCESS_DENIED);
        assertThatThrownBy(() -> service.uploadSlotClip("K7X2MQ", 2, 2L, GIF, "image/gif"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_INVALID_SLOT);
        assertThatThrownBy(() -> service.uploadSlotClip("K7X2MQ", 1, 2L, JPEG, "image/gif"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_INVALID_GIF);
    }

    @Test
    @DisplayName("칸 클립: 새 사진이 들어오거나 칸을 비우면 옛 클립은 옛 사진과 함께 대기열로 간다 — 칸에는 남지 않는다")
    void clip_goesWithItsPhoto() {
        FillSlot mine = slot(0);
        mine.fill(friend, "민지", "img000000000000000020", LocalDateTime.now(clock));
        mine.attachClip("clp000000000000000021");
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 0)).willReturn(Optional.of(mine));
        given(userRepository.findById(2L)).willReturn(Optional.of(friend));
        given(imageStore.store(any(), anyString(), any())).willReturn("img000000000000000022");
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(List.of(mine));
        ArgumentCaptor<FillQueueItem> queued = ArgumentCaptor.forClass(FillQueueItem.class);

        FillResponse replaced = service.fillSlot("K7X2MQ", 0, 2L, "민지", JPEG, "image/jpeg");

        verify(imageStore, never()).delete(session, "clp000000000000000021");
        assertThat(mine.getClipId()).isNull();
        assertThat(replaced.slots().get(0).clipUrl()).isNull();

        mine.attachClip("clp000000000000000023");
        service.clearSlot("K7X2MQ", 0, 2L);
        verify(queueRepository, org.mockito.Mockito.times(2)).save(queued.capture());
        assertThat(queued.getAllValues()).extracting(FillQueueItem::getClipId)
                .containsExactly("clp000000000000000021", "clp000000000000000023");
        assertThat(mine.getClipId()).isNull();
    }

    @Test
    @DisplayName("완성 GIF: 칸 사진이 바뀌면 더 맞지 않으므로 지워진다")
    void gif_droppedWhenASlotChanges() {
        String gifId = "gif000000000000000002";
        session.updateGifImageId(gifId);
        FillSlot empty = slot(0);
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdAndSlotIndex(10L, 0)).willReturn(Optional.of(empty));
        given(userRepository.findById(2L)).willReturn(Optional.of(friend));
        given(imageStore.store(any(), anyString(), any())).willReturn("img000000000000000003");
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(List.of(empty));

        FillResponse after = service.fillSlot("K7X2MQ", 0, 2L, "민지", JPEG, "image/jpeg");

        verify(imageStore).delete(session, gifId);
        assertThat(session.getGifImageId()).isNull();
        assertThat(after.gifUrl()).isNull();
    }

    @Test
    @DisplayName("이미지 조회: 세션 코드와 id가 함께 맞아야 한다")
    void image_requiresMatchingSessionAndId() {
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(imageStore.load(any(), anyString())).willThrow(new CustomException(ErrorCode.FILL_IMAGE_NOT_FOUND));

        assertThatThrownBy(() -> service.image("K7X2MQ", "nope"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_IMAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("프레임 바꾸기: 주인은 그림 프레임·무테로 갈아 끼우고, 이전 커스텀 요소 이미지는 지워진다")
    void updateFrame_ownerSwapsFrameAndDropsOldAssets() {
        String oldAssetUrl = "https://api.peakpic.app/fills/K7X2MQ/images/asset01";
        session.updateCustomFrameJson("{\"base\":\"White\",\"layers\":[{\"cx\":0.5,\"cy\":0.9,\"w\":0.3,\"rot\":0,\"url\":\"" + oldAssetUrl + "\"}],\"borderless\":true}");
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(session));
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(slot(0), slot(1), slot(2), slot(3)));

        FillResponse r = service.updateFrame("K7X2MQ", 1L, new UpdateFrameRequest("White", "Grid", "gyaru", null, null));

        verify(imageStore).delete(session, "asset01");
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
        FillCustomFrameDto custom = new FillCustomFrameDto("Black", List.of(new FillLayerDto(0.5f, 0.5f, 0.3f, 12f, "https://x/leftover", 2)), false);

        FillResponse r = service.updateFrame("K7X2MQ", 1L, new UpdateFrameRequest("Black", "Grid", null, custom, true));

        assertThat(r.borderless()).isTrue();
        assertThat(r.customFrame().borderless()).isTrue();
        assertThat(r.customFrame().layers()).singleElement().satisfies(l -> {
            assertThat(l.url()).isNull();
            assertThat(l.cell()).isEqualTo(2);
        });
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
