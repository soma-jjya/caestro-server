package com.caestro.server.domain.support.enums;

/**
 * 문의 상태 (#263). OPEN(사용자 차례가 끝나 운영자 답을 기다림) → ANSWERED(운영자가 답함) → CLOSED(운영자가 닫음).
 * 사용자가 이어 쓰면 어느 상태에서든 OPEN 으로 돌아온다.
 */
public enum SupportStatus {
    OPEN, ANSWERED, CLOSED
}
