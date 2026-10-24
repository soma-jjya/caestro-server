package com.caestro.server.domain.b2b.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 축제·매장이 운영하는 B2B 페이지. 최초 공개 뒤에는 공유 주소를 바꾸지 않는다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "b2b_projects", uniqueConstraints = @UniqueConstraint(name = "uk_b2b_project_slug", columnNames = "slug"))
public class B2bProject {
    public enum Status { DRAFT, LIVE, PAUSED }

    @Id @Column(length = 40)
    private String id;
    @Column(nullable = false, length = 60)
    private String name;
    @Column(nullable = false, length = 40)
    private String slug;
    @Column(nullable = false, length = 120)
    private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private Status status;
    private LocalDateTime publishedAt;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public void update(String name, String slug, String description, LocalDateTime now) {
        this.name = name;
        this.slug = slug;
        this.description = description;
        touch(now);
    }

    public void transition(Status status, LocalDateTime now) {
        this.status = status;
        if (status == Status.LIVE && publishedAt == null) publishedAt = now;
        touch(now);
    }

    public void touch(LocalDateTime now) { this.updatedAt = now; }
}
