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

    // 채운 사진의 id. 바이트가 어디 있는지(DB·S3)는 저장소가 알고 칸은 id만 든다 (#152).
    // FillSession.gifImageId와 같은 방식 — 저장소를 갈아도 이 컬럼은 그대로다.
    @Column(name = "image_id", length = 32)
    private String imageId;

    // 사진과 함께 찍힌 짧은 움직임(GIF)의 id. 앱 안 카메라로 채운 칸만 있고, 채운 사람만 붙인다.
    // 사진이 바뀌거나 칸을 비우면 함께 사라진다 — 새 사진에 옛 움직임은 맞지 않는다.
    @Column(name = "clip_id", length = 32)
    private String clipId;

    @Column
    private LocalDateTime filledAt;

    public boolean isEmpty() {
        return imageId == null;
    }

    public boolean isFilledBy(Long userId) {
        return filledBy != null && filledBy.getId().equals(userId);
    }

    public void fill(User user, String name, String newImageId, LocalDateTime now) {
        this.filledBy = user;
        this.filledByName = name;
        this.imageId = newImageId;
        this.filledAt = now;
        this.clipId = null;
    }

    public void attachClip(String newClipId) {
        this.clipId = newClipId;
    }

    public void clear() {
        this.filledBy = null;
        this.filledByName = null;
        this.imageId = null;
        this.filledAt = null;
        this.clipId = null;
    }
}
