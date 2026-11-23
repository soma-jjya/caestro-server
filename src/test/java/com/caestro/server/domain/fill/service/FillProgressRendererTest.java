package com.caestro.server.domain.fill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.entity.FillSlot;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.fill.repository.FillSlotRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.user.entity.User;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 진행 알림의 칸 수는 세션 배치를 따른다(#294) — 네컷은 "4칸 중", 한 컷은 한 장이면 완성이라 진행 알림이 없다. */
@ExtendWith(MockitoExtension.class)
class FillProgressRendererTest {

    @Mock private FillSessionRepository sessionRepository;
    @Mock private FillSlotRepository slotRepository;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), ZoneOffset.UTC);
    private FillProgressRenderer renderer;
    private User owner;
    private User friend;

    @BeforeEach
    void setUp() {
        renderer = new FillProgressRenderer(sessionRepository, slotRepository, clock);
        owner = User.builder().nickname("주인").role(User.Role.USER).build();
        ReflectionTestUtils.setField(owner, "id", 1L);
        friend = User.builder().nickname("민지").role(User.Role.USER).build();
        ReflectionTestUtils.setField(friend, "id", 2L);
    }

    private FillSession session(String layout) {
        return FillSession.builder().id(10L).code("K7X2MQ").owner(owner).frame("White").layout(layout)
                .expiresAt(LocalDateTime.now(clock).plusDays(7)).build();
    }

    /** 앞의 {@code filledByFriend}칸은 친구가 채우고 나머지는 빈 칸. */
    private List<FillSlot> slots(FillSession session, int count, int filledByFriend) {
        List<FillSlot> slots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FillSlot slot = FillSlot.builder().id(100L + i).session(session).slotIndex(i).build();
            if (i < filledByFriend) slot.fill(friend, "민지", "img" + i, LocalDateTime.now(clock).minusMinutes(i));
            slots.add(slot);
        }
        return slots;
    }

    @Test
    @DisplayName("네컷(Grid) 2칸이 차면 '4칸 중 2칸'")
    void fourSlotSessionCountsFour() {
        FillSession grid = session("Grid");
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(grid));
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(grid, 4, 2));

        Optional<PushMessage> rendered = renderer.render(PushMessage.fillProgressPlaceholder("K7X2MQ"));

        assertThat(rendered).hasValueSatisfying(m -> assertThat(m.body()).isEqualTo("4칸 중 2칸이 찼어요. 2칸만 더 채우면 완성이에요!"));
    }

    @Test
    @DisplayName("한 컷(Single)은 1칸이 차면 이미 완성 — '4칸 중 1칸'으로 나가지 않고 보내지 않는다")
    void singleSlotSessionNeverSendsProgress() {
        FillSession single = session("Single");
        given(sessionRepository.findByCode("K7X2MQ")).willReturn(Optional.of(single));
        given(slotRepository.findBySessionIdOrderBySlotIndex(10L)).willReturn(slots(single, 1, 1));

        assertThat(renderer.render(PushMessage.fillProgressPlaceholder("K7X2MQ"))).isEmpty();
    }
}
