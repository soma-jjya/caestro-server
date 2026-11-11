package com.caestro.server.domain.support.dto.request;

import com.caestro.server.domain.support.entity.SupportMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 스레드에 말 한 마디(사용자의 이어 쓰기, 운영자의 답변 모두). */
public record CreateSupportMessageRequest(

        @NotBlank(message = "내용은 필수입니다")
        @Size(max = SupportMessage.BODY_MAX, message = "내용은 2000자 이하입니다")
        String body
) {
}
