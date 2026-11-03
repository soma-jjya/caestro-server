package com.caestro.server.domain.inquiry.event;

import com.caestro.server.domain.inquiry.enums.InquiryType;

/** 문의가 저장된 순간. 커밋 뒤에만 처리된다 — 웹훅이 가리키는 id 가 관리자 페이지에 없는 일이 없도록. */
public record InquiryReceivedEvent(Long id, InquiryType type, String organization, String contactName,
                                   String phone, String utmContent) {
}
