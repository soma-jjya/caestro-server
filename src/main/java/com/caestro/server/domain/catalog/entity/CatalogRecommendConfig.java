package com.caestro.server.domain.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
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
 * 포즈추천 알고리즘 설정의 한 버전 (#260, contracts/recommend-config.md). 장소 읽기 문턱값·점수 가중치·장소→풀 매핑(params)과
 * 바꿔 끼울 장소 헤드 JSON·ONNX 모델을 묶는다. 헤드·모델은 {@code catalog_images} 행(자산 저장소, 운영 S3+CDN)이고 null 이면 앱 번들 것.
 * 게시(active)는 한 번에 하나이며, 게시 중인 버전은 고치거나 지울 수 없다(스튜디오가 복제해서 고친다).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "catalog_recommend_configs")
public class CatalogRecommendConfig {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 500)
    private String note;

    // 검증·정리한 params(JSON 객체). 빈 설정은 "{}"
    @Column(nullable = false, columnDefinition = "TEXT")
    private String paramsJson;

    // 장소 헤드 JSON(application/json). null 이면 앱 번들 헤드
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "head_id")
    private CatalogImage head;

    // 장소 ONNX 모델(application/octet-stream). null 이면 앱 번들 모델
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id")
    private CatalogImage model;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // 마지막으로 게시한 시각. 게시를 내리거나 다른 버전을 게시해도 남는다(이력)
    private LocalDateTime publishedAt;

    public void update(String name, String note, String paramsJson, CatalogImage head, CatalogImage model, LocalDateTime now) {
        this.name = name;
        this.note = note;
        this.paramsJson = paramsJson;
        this.head = head;
        this.model = model;
        this.updatedAt = now;
    }

    public void publish(LocalDateTime now) {
        this.active = true;
        this.publishedAt = now;
    }
}
