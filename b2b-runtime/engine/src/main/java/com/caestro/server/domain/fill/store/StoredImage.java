package com.caestro.server.domain.fill.store;

/**
 * 저장소에서 꺼낸 이미지 한 장. 저장 위치(DB·S3)와 무관한 형태로만 주고받는다 (#152).
 * JPA 엔티티를 밖으로 내보내지 않기 위해 둔 경계 — 이 record가 바뀌지 않으면 저장소를 갈아도 서비스는 그대로다.
 */
public record StoredImage(String id, String contentType, byte[] data) {
}
