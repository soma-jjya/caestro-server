package com.caestro.server.domain.deveval.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 평가 사진을 찍을 때 쓴 '직접 올린 사진' 가이드의 이미지 (#266). 그 가이드는 사용자의 폰에만 있어서(풀 가이드는 공개 CDN)
 * 관리자가 사진 위에 겹쳐 보려면 앱이 올려야 한다. 사람이 담긴 사진이라 평가 사진처럼 RDS 에만 두고
 * {@code GET /admin/dev/evaluations/{id}/guide/{kind}} 로만 읽는다. 한 기록에 kind(photo 실사·line 라인)마다 하나, id 는 "기록id:kind".
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Entity
@Table(name = "dev_evaluation_guides", indexes = @Index(name = "idx_dev_evaluation_guides_evaluation", columnList = "evaluationId"))
public class DevEvaluationGuide {

    @Id
    @Column(length = 72)
    private String id;

    @Column(nullable = false, length = 64)
    private String evaluationId;

    // photo | line
    @Column(nullable = false, length = 10)
    private String kind;

    @Column(nullable = false, length = 20)
    private String contentType;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGBLOB")
    private byte[] data;

    public static String idOf(String evaluationId, String kind) {
        return evaluationId + ":" + kind;
    }
}
