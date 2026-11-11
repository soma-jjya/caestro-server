package com.caestro.server.domain.support.dto.request;

import com.caestro.server.domain.support.enums.SupportStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * 관리자 부분 수정. 보낸 것만 바꾼다. 상태는 CLOSED(닫기)·OPEN(다시 열기)만 — ANSWERED 는 답변으로만 된다.
 * {@code adminMemo} 는 빈 문자열이면 지운다, null 이면 그대로.
 */
public record UpdateSupportTicketRequest(

        SupportStatus status,

        @Size(max = 2000, message = "메모는 2000자 이하입니다")
        String adminMemo
) {
    @AssertTrue(message = "상태는 CLOSED 또는 OPEN 만 보낼 수 있습니다")
    public boolean isStatusAllowed() {
        return status == null || status == SupportStatus.CLOSED || status == SupportStatus.OPEN;
    }
}
