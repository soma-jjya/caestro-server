package com.caestro.server.domain.catalog.store;

/** 저장소에서 읽은 자산 한 건. 서버가 바이트를 중계할 때(RDS 구현) 쓴다. */
public record CatalogAsset(String id, String contentType, byte[] data) {
}
