package com.caestro.server.domain.inquiry.event;

import com.caestro.server.domain.inquiry.enums.ContactMethod;
import com.caestro.server.domain.inquiry.enums.InquiryType;

/**
 * 문의가 저장된 순간. 커밋 뒤에만 처리된다 — 웹훅이 가리키는 id 가 관리자 페이지에 없는 일이 없도록.
 * {@code contact} 는 고른 방법({@code contactMethod})의 값 하나다(전화번호·이메일·인스타 아이디).
 */
public record InquiryReceivedEvent(Long id, InquiryType type, String organization, String contactName,
                                   ContactMethod contactMethod, String contact, String utmContent) {
}
