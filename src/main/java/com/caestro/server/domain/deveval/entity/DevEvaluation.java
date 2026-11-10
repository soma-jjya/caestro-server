package com.caestro.server.domain.deveval.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 앱 개발자 모드의 사진 평가 한 건 (#260, contracts/dev-evaluations.md): 만족도(rating/ratingMax), 가이드 유무와 그 가이드,
 * 촬영 때의 포즈 일치도와 점수 매기기 결과, 기기·앱 버전. id 는 앱이 사진마다 만든 UUID(clientId)라 다시 보내면 덮어쓴다.
 * 사진 바이트는 {@link DevEvaluationPhoto}(같은 id)에 따로 둔다 — 목록 조회가 블롭을 읽지 않게.
 * 사용자는 탈퇴해도 행이 남으므로(users 는 지우지 않는다) user_id 는 외래키 없이 숫자로만 둔다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "dev_evaluations", indexes = {
        // 사용자당 개수(상한 3000)와 관리자 목록의 사용자 거르기
        @Index(name = "idx_dev_evaluations_user", columnList = "user_id"),
        // 관리자 목록은 최근 고친 것부터
        @Index(name = "idx_dev_evaluations_updated", columnList = "updated_at")})
public class DevEvaluation {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false)
    private int ratingMax;

    // 가이드를 화면에 띄우고 찍었는지(앱이 자동으로 채우고 개발자가 고칠 수 있다)
    @Column(nullable = false)
    private boolean guide;

    @Column(length = 40)
    private String guideVersion;

    @Column(length = 80)
    private String guideShot;

    // photo(실사) | line(라인)
    @Column(length = 20)
    private String guideStyle;

    // 셔터 순간의 포즈 일치도(0..100)
    private Double shutterScore;

    // 갤러리 점수 매기기(PhotoScorer) 결과. 각 칸은 비어 있을 수 있다
    private Double scoreTotal;
    private Double scorePose;
    private Double scorePlace;
    private Double scoreShape;

    // photo | fourcut
    @Column(nullable = false, length = 10)
    private String mediaType;

    private LocalDateTime takenAt;

    @Column(nullable = false)
    private LocalDateTime ratedAt;

    private Integer width;
    private Integer height;

    @Column(length = 40)
    private String appVersion;

    @Column(length = 80)
    private String device;

    @Column(length = 500)
    private String note;

    // 앱이 덧붙인 자유 JSON 객체(≤ 4KB). 그대로 돌려준다
    @Column(columnDefinition = "TEXT")
    private String extraJson;

    // 사진이 있으면 그 크기와 종류(image/jpeg·png·webp). 없으면 null
    private Integer photoBytes;

    @Column(length = 20)
    private String photoContentType;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static DevEvaluation create(String id, Long userId, DevEvaluationValues values, LocalDateTime now) {
        DevEvaluation e = new DevEvaluation();
        e.id = id;
        e.userId = userId;
        e.createdAt = now;
        e.apply(values, now);
        return e;
    }

    /** 기록을 통째로 덮어쓴다. 사진은 그대로 둔다. */
    public void apply(DevEvaluationValues v, LocalDateTime now) {
        this.rating = v.rating();
        this.ratingMax = v.ratingMax();
        this.guide = v.guide();
        this.guideVersion = v.guideVersion();
        this.guideShot = v.guideShot();
        this.guideStyle = v.guideStyle();
        this.shutterScore = v.shutterScore();
        this.scoreTotal = v.scoreTotal();
        this.scorePose = v.scorePose();
        this.scorePlace = v.scorePlace();
        this.scoreShape = v.scoreShape();
        this.mediaType = v.mediaType();
        this.takenAt = v.takenAt();
        this.ratedAt = v.ratedAt();
        this.width = v.width();
        this.height = v.height();
        this.appVersion = v.appVersion();
        this.device = v.device();
        this.note = v.note();
        this.extraJson = v.extraJson();
        this.updatedAt = now;
    }

    public void attachPhoto(int bytes, String contentType, LocalDateTime now) {
        this.photoBytes = bytes;
        this.photoContentType = contentType;
        this.updatedAt = now;
    }

    public boolean hasPhoto() {
        return photoBytes != null;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
