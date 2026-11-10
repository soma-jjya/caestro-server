package com.caestro.server.global.exception;

import com.caestro.server.global.exception.error.ErrorCode;
import lombok.Getter;

@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;

    // 무엇이 틀렸는지 덧붙이는 말(선택). 있으면 응답 message 가 "기본 문구: 덧붙인 말" 이 된다 (#260 추천 설정·헤드 검증)
    private final String detail;

    public CustomException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public CustomException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    /** 응답 본문에 싣는 문구. */
    public String responseMessage() {
        return detail == null || detail.isBlank() ? errorCode.getMessage() : errorCode.getMessage() + ": " + detail;
    }
}
