package com.caestro.server.domain.catalog.dto.response;

/** 추천 설정에 묶인 파일(장소 헤드 JSON·ONNX 모델): id, 공개 주소(운영은 CDN), 바이트 수. */
public record RecommendFileDto(String id, String url, long bytes) {
}
