package com.caestro.server.domain.fill.entity;

import com.caestro.server.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 세션의 대기열 한 줄: 칸에서 밀려난 사진. 누구든 채워진 칸을 바꾸거나 비울 수 있게 되면서(#fill-queue)
 * 이전 사진은 지우지 않고 여기로 내려온다 — 다른 참여자가 "아까 사진이 이랬지"를 볼 수 있고,
 * 어느 칸으로든 되돌릴 수 있다({@code restore}). 지우기(X)는 누구나 할 수 있다.
 * 사진·클립 바이트는 칸과 같은 저장소에 그대로 있고 id만 옮겨 온다. 세션이 사라지면 함께 사라진다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "fill_queue", indexes = @Index(name = "idx_fill_queue_session", columnList = "session_id"))
public class FillQueueItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private FillSession session;

    // 어느 칸에 있었는지(0~3) — 되돌리기의 기본 칸이자 썸네일의 번호
    @Column(name = "from_slot", nullable = false)
    private int fromSlot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "filled_by_id")
    private User filledBy;

    @Column(length = 40)
    private String filledByName;

    @Column(name = "image_id", nullable = false, length = 32)
    private String imageId;

    // 그 사진과 함께 찍힌 클립. 되돌리면 칸으로 같이 돌아간다
    @Column(name = "clip_id", length = 32)
    private String clipId;

    @Column
    private LocalDateTime filledAt;

    @Column(nullable = false)
    private LocalDateTime queuedAt;

    public boolean isFilledBy(Long userId) {
        return filledBy != null && filledBy.getId().equals(userId);
    }

    /** 칸의 지금 내용을 대기열 한 줄로. 칸은 비어 있지 않아야 한다. */
    public static FillQueueItem of(FillSlot slot, LocalDateTime now) {
        return FillQueueItem.builder()
                .session(slot.getSession())
                .fromSlot(slot.getSlotIndex())
                .filledBy(slot.getFilledBy())
                .filledByName(slot.getFilledByName())
                .imageId(slot.getImageId())
                .clipId(slot.getClipId())
                .filledAt(slot.getFilledAt())
                .queuedAt(now)
                .build();
    }
}
