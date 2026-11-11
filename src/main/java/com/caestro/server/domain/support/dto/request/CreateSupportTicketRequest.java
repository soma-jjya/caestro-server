package com.caestro.server.domain.support.dto.request;

import com.caestro.server.domain.support.entity.SupportMessage;
import com.caestro.server.domain.support.enums.SupportCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 문의 작성 (#263). 분류·제목·내용은 사용자가 쓰고, 앱 버전·플랫폼·OS·기기명은 앱이 자동으로 붙인다(없어도 된다).
 */
public record CreateSupportTicketRequest(

        @NotNull(message = "문의 분류는 필수입니다")
        SupportCategory category,

        @NotBlank(message = "제목은 필수입니다")
        @Size(max = 80, message = "제목은 80자 이하입니다")
        String title,

        @NotBlank(message = "문의 내용은 필수입니다")
        @Size(max = SupportMessage.BODY_MAX, message = "문의 내용은 2000자 이하입니다")
        String body,

        @Size(max = 30, message = "앱 버전은 30자 이하입니다")
        String appVersion,

        @Size(max = 10, message = "플랫폼은 10자 이하입니다")
        String platform,

        @Size(max = 40, message = "OS 버전은 40자 이하입니다")
        String osVersion,

        @Size(max = 60, message = "기기명은 60자 이하입니다")
        String deviceModel
) {
}
