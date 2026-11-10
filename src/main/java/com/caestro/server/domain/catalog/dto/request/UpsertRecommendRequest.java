package com.caestro.server.domain.catalog.dto.request;

import java.util.Map;

/**
 * 추천 설정 버전 만들기·고치기 본문 (contracts/recommend-config.md). 고치기(PUT)도 통째로 바꾼다 — 보내지 않은 headId·modelId 는 앱 번들로.
 * params 는 모르는 키를 400 으로 막아야 해서(오타가 조용히 무시되지 않게) 형태 없는 맵으로 받아 서비스가 검증한다.
 */
public record UpsertRecommendRequest(String name, String note, Map<String, Object> params, String headId, String modelId) {
}
