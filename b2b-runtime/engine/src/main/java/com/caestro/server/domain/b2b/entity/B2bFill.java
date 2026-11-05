package com.caestro.server.domain.b2b.entity;

import com.caestro.server.domain.fill.entity.FillSession;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 기존 네컷 세션의 B2B 소속과 불변 프레임. 원본 프레임 행이 없어져도 유지한다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "b2b_fills")
public class B2bFill {
    @Id @Column(length = 40)
    private String id;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_b2b_fill_session"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private FillSession session;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "project_id", nullable = false)
    private B2bProject project;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "image_id", nullable = false)
    private B2bImage image;
    @Lob @Column(nullable = false, columnDefinition = "TEXT")
    private String frameJson;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
