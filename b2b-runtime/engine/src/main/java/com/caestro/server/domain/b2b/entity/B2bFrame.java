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
    public enum Layout { STRIP, GRID }

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
