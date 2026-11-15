package com.caestro.server.domain.deveval.dto.request;

/**
 * 관리자 화면에서 사진마다 손으로 체크하는 '좋아요'(#291). 갤러리 하트(즐겨찾기)는 폰 안에만 있어 서버로 오지 않는다.
 *
 * @param liked true 좋아요, false 해제. null 은 400
 */
public record LikeRequest(Boolean liked) {
}
