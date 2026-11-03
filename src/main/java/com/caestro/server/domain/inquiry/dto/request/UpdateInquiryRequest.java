package com.caestro.server.domain.inquiry.dto.request;

import com.caestro.server.domain.inquiry.enums.InquiryStatus;
import jakarta.validation.constraints.Size;

/** 관리자의 부분 수정. null 인 필드는 건드리지 않는다. linkedProjectId 를 빈 문자열로 보내면 연결을 푼다. */
public record UpdateInquiryRequest(

        InquiryStatus status,

        @Size(max = 2000, message = "메모는 2000자 이하입니다")
        String adminMemo,

        @Size(max = 40, message = "프로젝트 id 가 올바르지 않습니다")
        String linkedProjectId
) {
}
