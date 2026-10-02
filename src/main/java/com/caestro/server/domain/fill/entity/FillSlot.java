package com.caestro.server.domain.fill.entity;

import com.caestro.server.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 세션의 칸 하나(0~3). 비어 있거나, 누가 어떤 사진으로 언제 채웠는지를 든다.
 * 먼저 채운 사람이 임자다 — 다른 사람이 채운 칸에 덧쓰는 요청은 409로 막고, 비우기는 본인 칸이거나 세션 주인만 할 수 있다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "fill_slots", uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "slot_index"}))
public class FillSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private FillSession session;

    @Column(name = "slot_index", nullable = false)
    private int slotIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "filled_by_id")
    private User filledBy;

    // 채운 사람이 밝힌 이름(웹 참여자용). 없으면 유저 닉네임, 그것도 없으면 클라이언트가 "친구"로 보여준다.
    @Column(length = 40)
    private String filledByName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_id")
    private FillImage image;

    @Column
    private LocalDateTime filledAt;

    public boolean isEmpty() {
        return image == null;
    }

    public boolean isFilledBy(Long userId) {
        return filledBy != null && filledBy.getId().equals(userId);
    }

    public void fill(User user, String name, FillImage newImage, LocalDateTime now) {
        this.filledBy = user;
        this.filledByName = name;
        this.image = newImage;
        this.filledAt = now;
    }

    public void clear() {
        this.filledBy = null;
        this.filledByName = null;
        this.image = null;
        this.filledAt = null;
    }
}
