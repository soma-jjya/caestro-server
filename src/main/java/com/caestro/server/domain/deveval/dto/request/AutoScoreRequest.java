package com.caestro.server.domain.deveval.dto.request;

/**
 * 관리자 화면이 평가 사진에서 다시 매긴 사진 점수(#289). 폰의 점수 매기기(PhotoScorer)와 같은 계산을 브라우저에서 한 결과다.
 *
 * @param status SCORED(점수 있음) · NOBODY(사진에서 사람을 못 찾음) · NO_GUIDE_POSE(가이드의 관절을 못 찾음)
 * @param total  SCORED 때 0..100 (포즈 × 자리 × 형태)
 * @param pose   SCORED 때 0..100 (포즈만)
 * @param place  0..1, 자리·크기 몫. 판단할 수 없으면 null
 * @param shape  0..1, 형태 몫. 판단할 수 없으면 null
 * @param algo   관리자 쪽 계산식 버전(1 이상). 바꾸면 다시 매긴다
 */
public record AutoScoreRequest(String status, Double total, Double pose, Double place, Double shape, Integer algo) {
}
