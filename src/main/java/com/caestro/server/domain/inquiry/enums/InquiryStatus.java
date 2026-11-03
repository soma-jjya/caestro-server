package com.caestro.server.domain.inquiry.enums;

/** 영업 진행 단계. 엄격한 상태 기계가 아니라 메모에 가깝다 — 어느 상태로든 바꿀 수 있고, NEW 를 벗어난 첫 순간만 handledAt 에 남는다. */
public enum InquiryStatus {
    NEW, CONTACTED, PROPOSAL_SENT, WON, LOST, SPAM
}
