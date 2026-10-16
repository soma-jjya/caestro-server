package com.caestro.server.domain.fill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 입력 검증 결함 3건의 재현·방어 (#151).
 * ① 표시 이름이 컬럼(40자)보다 길면 DB가 거부해 500 + 5xx 알람이 됐다 → 잘라서 받는다.
 * ② 코드 대문자화를 메서드마다 기억해야 했고 delete·image가 빠뜨려 소문자 코드에 404를 줬다 → 한 곳으로 모았다.
 * ③ 남의 칸이면 사진을 저장하기 전에 거절한다(고아 이미지 방지). 동시 경합은 {@code FillSlotRaceTest}가 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FillInputValidationTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x10, 0x20, 0x30, 0x40};

    @Mock private FillSessionRepository sessionRepository;
    @Mock private FillSlotRepository slotRepository;
    @Mock private FillImageRepository imageRepository;
    @Mock private UserRepository userRepository;
    @Mock private FillCodeGenerator codeGenerator;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC);
    private FillService service;
    private User owner;
    private FillSession session;
    private FillSlot slot;

    @BeforeEach
    void setUp() {
        service = new FillService(sessionRepository, slotRepository, imageRepository, userRepository,
                codeGenerator, new ObjectMapper(), clock);
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://api.peakpic.app");
        ReflectionTestUtils.setField(service, "ttlDays", 7L);
        ReflectionTestUtils.setField(service, "maxImageBytes", 3 * 1024 * 1024);
        ReflectionTestUtils.setField(service, "maxGifBytes", 16 * 1024 * 1024);

        owner = User.builder().nickname("주인").role(User.Role.USER).build();
        ReflectionTestUtils.setField(owner, "id", 1L);
        session = FillSession.builder().id(10L).code("K7X2MQ").owner(owner).frame("Black").layout("Strip")
                .expiresAt(LocalDateTime.now(clock).plusDays(7)).createdAt(LocalDateTime.now(clock)).build();
        slot = FillSlot.builder().id(100L).session(session).slotIndex(0).build();

        when(sessionRepository.findByCode("K7X2MQ")).thenReturn(Optional.of(session));
        when(slotRepository.findBySessionIdAndSlotIndex(10L, 0)).thenReturn(Optional.of(slot));
        when(slotRepository.findBySessionIdOrderBySlotIndex(10L)).thenReturn(List.of(slot));
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(codeGenerator.imageId()).thenReturn("img0000000000000000001");
        when(imageRepository.save(any(FillImage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("[재현→방어] 표시 이름이 40자를 넘으면 자른다 — 그대로 저장하면 DB가 거부해 500이었다")
    void longName_isTruncated() {
        service.fillSlot("K7X2MQ", 0, 1L, "가".repeat(45), JPEG, "image/jpeg");

        assertThat(slot.getFilledByName()).hasSize(40);
    }

    @Test
    @DisplayName("이름을 안 보내면 닉네임을 쓰고, 닉네임도 40자를 넘으면 자른다")
    void blankName_fallsBackToNickname_alsoTruncated() {
        ReflectionTestUtils.setField(owner, "nickname", "나".repeat(50));

        service.fillSlot("K7X2MQ", 0, 1L, "   ", JPEG, "image/jpeg");

        assertThat(slot.getFilledByName()).hasSize(40);
    }

    @Test
    @DisplayName("[회귀] 40자 이내 이름은 앞뒤 공백만 지우고 그대로 저장한다")
    void shortName_keptAsIs() {
        service.fillSlot("K7X2MQ", 0, 1L, "  민지  ", JPEG, "image/jpeg");

        assertThat(slot.getFilledByName()).isEqualTo("민지");
    }

    @Test
    @DisplayName("[재현→방어] 소문자 코드로도 세션을 없앨 수 있다 — delete가 대문자화를 빠뜨려 404였다")
    void delete_acceptsLowercaseCode() {
        service.delete("k7x2mq", 1L);

        verify(sessionRepository).findByCode("K7X2MQ");
        verify(sessionRepository).delete(session);
    }

    @Test
    @DisplayName("[재현→방어] 소문자 코드로도 이미지를 받을 수 있다 — image도 같은 구멍이었다")
    void image_acceptsLowercaseCode() {
        FillImage stored = FillImage.builder().id("img0000000000000000001").session(session)
                .contentType("image/jpeg").data(JPEG).sizeBytes(JPEG.length).build();
        when(imageRepository.findByIdAndSessionId("img0000000000000000001", 10L)).thenReturn(Optional.of(stored));

        FillImage found = service.image(" k7x2mq ", "img0000000000000000001");

        assertThat(found.getId()).isEqualTo("img0000000000000000001");
        verify(sessionRepository).findByCode("K7X2MQ");
    }

    @Test
    @DisplayName("남이 채운 칸이면 사진을 저장하기 전에 409로 끝난다 — 주인 없는 사진이 남지 않는다")
    void takenSlot_rejectedBeforeStoringImage() {
        User friend = User.builder().nickname("친구").role(User.Role.USER).build();
        ReflectionTestUtils.setField(friend, "id", 2L);
        FillImage theirs = FillImage.builder().id("imgOther00000000000001").session(session)
                .contentType("image/jpeg").data(JPEG).sizeBytes(JPEG.length).build();
        slot.fill(friend, "친구", theirs, LocalDateTime.now(clock));

        assertThatThrownBy(() -> service.fillSlot("K7X2MQ", 0, 1L, "주인", JPEG, "image/jpeg"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILL_SLOT_TAKEN);

        verify(imageRepository, never()).save(any(FillImage.class));
    }

    @Test
    @DisplayName("[회귀] 제목은 여전히 30자까지 자른다")
    void title_stillTruncatedAt30() {
        assertThat(FillService.normalizeTitle(" 제주   여행 ")).isEqualTo("제주 여행");
        assertThat(FillService.normalizeTitle("가".repeat(40))).hasSize(30);
        assertThat(FillService.normalizeTitle("   ")).isNull();
        assertThat(FillService.normalizeTitle(null)).isNull();
    }

    @Test
    @DisplayName("없는 코드는 그대로 404 — 정규화가 조회 실패를 가리지 않는다")
    void unknownCode_stillNotFound() {
        when(sessionRepository.findByCode(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("zzzzzz", 1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILL_NOT_FOUND);
    }
}
