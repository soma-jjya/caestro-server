package com.caestro.server.domain.fill.event;

import java.time.LocalDateTime;

/**
 * 주인이 아닌 사람이 함께 채우기 칸에 사진을 넣었고, 아직 네 칸이 다 차지 않은 순간 (#274). 칸을 채울 때마다 발행되고,
 * 아웃박스가 세션별로 한 행에 묶는다(조용해지면 한 통, 한 시간에 한 통). 칸을 채운 트랜잭션 안에서 처리된다.
 */
public record FillProgressEvent(Long sessionId, Long ownerId, String code, LocalDateTime at) {
}
