package com.caestro.server.domain.notification.entity;

/**
 * 아웃박스 행의 상태 (#272).
 * PENDING(보낼 차례 기다림) → PROCESSING(릴레이가 집어 감, lease 만료 전) → [스트림 모드 #273: RELAYED(큐에 넣음, 소비자 차례)] → SENT / EXPIRED(유효 시한 지나 안 보냄) / DEAD(재시도 한도 초과, 사람이 본다).
 * 실패하면 PROCESSING → PENDING 으로 돌아오며 attempts 와 not_before 가 늘어난다.
 */
public enum OutboxStatus {
    PENDING, PROCESSING, RELAYED, SENT, EXPIRED, DEAD
}
