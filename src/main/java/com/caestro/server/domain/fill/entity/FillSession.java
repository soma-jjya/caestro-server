package com.caestro.server.domain.fill.entity;

import com.caestro.server.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 함께 채우기 세션: 링크로 공유된 네컷 프레임 하나. 네 칸({@link FillSlot})을 서로 다른 폰에서 각자 채우고,
 * 다 차면 누구든 자기 기기에서 합성한다(서버는 합성하지 않는다). 사진과 커스텀 프레임 요소는 {@link FillImage}로 보관하며
 * 만료({@link #expiresAt}) 후 배치가 통째로 지운다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "fill_sessions")
public class FillSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 링크와 초대 코드에 쓰이는 6자리(혼동 글자 제외). 앱은 대문자로 정규화해 보낸다.
    @Column(nullable = false, unique = true, length = 12)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    // 기본 프레임 색: Black | White (앱의 FourCutFrame 이름)
    @Column(nullable = false, length = 20)
    private String frame;

    // 배치: Strip(1×4) | Grid(2×2) (앱의 FourCutLayout 이름)
    @Column(nullable = false, length = 20)
    private String layout;

    // 내장 그림 프레임 id (gyaru/dessert/cute), 없으면 null
    @Column(length = 40)
    private String artFrameId;

    // 사용자 제작 프레임: {"base":..,"layers":[{"cx","cy","w","rot","url","cell"}]} — 요소 PNG는 FillImage로 올라오며 url이 채워진다
    @Lob
    @Column(columnDefinition = "TEXT")
    private String customFrameJson;

    // 주인이 붙인 제목. 없으면 클라이언트가 코드를 보여 준다.
    @Column(length = 30)
    private String title;

    // 무테: 여백·간격·하단 없이 사진이 맞닿는다. 커스텀 프레임의 무테 여부도 여기에 맞춰 둔다.
    @Column(nullable = false)
    private boolean borderless;

    // 완성된 네컷의 움직이는 GIF(앱이 링크로 공유할 때 올림). FillImage id; 칸이나 프레임이 바뀌면 지운다.
    @Column(length = 32)
    private String gifImageId;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public boolean isOwnedBy(Long userId) {
        return owner != null && owner.getId().equals(userId);
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public void updateCustomFrameJson(String json) {
        this.customFrameJson = json;
    }

    public void updateGifImageId(String gifImageId) {
        this.gifImageId = gifImageId;
    }

    /** 주인이 프레임을 바꿀 때: 바탕색·배치·그림 프레임·커스텀 프레임·무테를 한 번에 갈아 끼운다. */
    public void updateFrame(String frame, String layout, String artFrameId, String customFrameJson, boolean borderless) {
        this.frame = frame;
        this.layout = layout;
        this.artFrameId = artFrameId;
        this.customFrameJson = customFrameJson;
        this.borderless = borderless;
    }
}
