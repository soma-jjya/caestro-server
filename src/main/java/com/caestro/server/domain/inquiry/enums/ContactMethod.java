package com.caestro.server.domain.inquiry.enums;

/**
 * 연락받을 방법. 폼은 셋 중 하나를 고르고 그 칸 하나만 보낸다(2026-11-07, contracts/b2b-inquiries.md).
 * 이 값이 없던 옛 폼·옛 행은 전화로 본다.
 */
public enum ContactMethod {
    PHONE, EMAIL, INSTAGRAM
}
