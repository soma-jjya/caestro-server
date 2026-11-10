package com.caestro.server.domain.deveval.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 평가 사진 바이트 (#260). 사람 얼굴이 담겨 있어 카탈로그 자산 저장소(운영 S3 + 공개 CDN)에 두지 않고 RDS 에만 둔다 —
 * 읽는 길은 스튜디오 키가 필요한 {@code GET /admin/dev/evaluations/{id}/photo} 하나뿐이다. id 는 평가 기록 id 와 같다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Entity
@Table(name = "dev_evaluation_photos")
public class DevEvaluationPhoto {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 20)
    private String contentType;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGBLOB")
    private byte[] data;
}
