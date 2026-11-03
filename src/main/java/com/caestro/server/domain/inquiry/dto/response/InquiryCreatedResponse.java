package com.caestro.server.domain.inquiry.dto.response;

/** 접수 응답. 허니팟에 걸린 요청도 같은 모양(id 는 0)으로 돌려줘 봇에게 실패를 알리지 않는다. */
public record InquiryCreatedResponse(Long id) {
}
