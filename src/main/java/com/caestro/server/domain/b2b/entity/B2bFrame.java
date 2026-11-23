package com.caestro.server.domain.b2b.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 현재 선택 가능한 프레임 설정. 참여 시점의 설정은 B2bFill에 따로 복사된다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "b2b_frames", indexes = @Index(name = "idx_b2b_frame_project", columnList = "project_id,display_order"))
public class B2bFrame {
    /**
     * 사진 배치: STRIP(1×4)·GRID(2×2)·SINGLE(1×1, #294). {@link #slots()} 는 사진 창 수이자 이 배치로 만드는 함께 채우기 세션의 칸 수.
     * 운영 DB 의 layout 열은 MySQL ENUM 이라 값을 늘리면 손으로 ALTER 한다(contracts/sql/294-b2b-single-layout.sql).
     */
    public enum Layout {
        STRIP(4), GRID(4), SINGLE(1);

        private final int slots;

        Layout(int slots) {
            this.slots = slots;
        }

        /** 공개하려면 창이 정확히 이만큼 있어야 하고, 저장할 때도 이보다 많을 수 없다. */
        public int slots() {
            return slots;
        }
    }

    @Id @Column(length = 40)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "project_id", nullable = false)
    private B2bProject project;
    @Column(nullable = false, length = 30)
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private Layout layout;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "image_id")
    private B2bImage image;
    @Lob @Column(nullable = false, columnDefinition = "TEXT")
    private String windowsJson;
    @Column(nullable = false)
    private int displayOrder;
    @Column(nullable = false)
    private int version;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 실제 설정 변경에만 버전을 올린다. 같은 PUT의 재전송에는 유지한다. */
    public void update(String name, Layout layout, B2bImage image, String windowsJson, int order) {
        String oldImageId = this.image == null ? null : this.image.getId();
        String newImageId = image == null ? null : image.getId();
        if (!Objects.equals(this.name, name) || this.layout != layout || !Objects.equals(oldImageId, newImageId)
                || !Objects.equals(this.windowsJson, windowsJson) || this.displayOrder != order) {
            this.version++;
        }
        this.name = name;
        this.layout = layout;
        this.image = image;
        this.windowsJson = windowsJson;
        this.displayOrder = order;
    }
}
