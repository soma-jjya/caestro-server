package com.caestro.server.domain.fill.service;

import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.entity.FillSlot;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.fill.repository.FillSlotRepository;
import com.caestro.server.domain.notification.service.OutboxMessageRenderer;
import com.caestro.server.domain.push.sender.PushMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 친구 칸 채움 묶음 알림(#274)의 문구를 **보내는 순간의 세션**으로 만든다. 묶인 동안 더 채워진 칸까지 세고, 그 사이 완성됐거나
 * 세션이 사라졌으면 보내지 않는다(빈 값 → 행 CANCELLED). 친구는 주인이 아닌, 지금 칸에 사진이 있는 사람.
 */
@Component
@RequiredArgsConstructor
public class FillProgressRenderer implements OutboxMessageRenderer {

    private final FillSessionRepository sessionRepository;
    private final FillSlotRepository slotRepository;
    private final Clock clock;

    @Override
    public String type() {
        return PushMessage.TYPE_FILL_PROGRESS;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PushMessage> render(PushMessage stored) {
        String code = stored.data().get("code");
        if (code == null) return Optional.empty();
        // 1. 세션이 살아 있고 아직 완성 알림 전인가
        Optional<FillSession> found = sessionRepository.findByCode(code);
        if (found.isEmpty() || found.get().isExpired(LocalDateTime.now(clock)) || found.get().getCompletedNotifiedAt() != null) {
            return Optional.empty();
        }
        FillSession session = found.get();
        Long ownerId = session.getOwner().getId();
        // 2. 지금 칸 상태: 찬 칸 수, 친구(주인 아닌 사람) 수와 가장 최근에 채운 친구
        List<FillSlot> slots = slotRepository.findBySessionIdOrderBySlotIndex(session.getId());
        int filled = (int) slots.stream().filter(slot -> !slot.isEmpty()).count();
        List<FillSlot> byFriends = slots.stream()
                .filter(slot -> !slot.isEmpty() && slot.getFilledBy() != null && !slot.getFilledBy().getId().equals(ownerId))
                .sorted(Comparator.comparing(FillSlot::getFilledAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
        // 칸 수는 세션 배치를 따른다(한 컷은 1칸 — 한 장이면 완성이라 진행 알림이 나가지 않는다, #294)
        int total = FillService.slotCount(session.getLayout());
        if (byFriends.isEmpty() || filled >= total) {
            return Optional.empty();
        }
        int friends = (int) byFriends.stream().map(slot -> slot.getFilledBy().getId()).distinct().count();
        // 3. 문구
        return Optional.of(PushMessage.fillProgress(code, session.getTitle(), byFriends.get(0).getFilledByName(), friends, filled, total));
    }
}
