package com.caestro.server.domain.inquiry.dto.request;

import com.caestro.server.domain.inquiry.enums.ContactMethod;
import com.caestro.server.domain.inquiry.enums.InquiryType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 광고 랜딩 문의 폼. 필수는 유형·업체명·성함·연락처 하나·개인정보 동의. {@code fax} 는 화면에 없는 허니팟 — 값이 있으면 봇으로 보고 저장하지 않는다.
 * 연락처는 {@code contactMethod} 로 고른 칸(전화·이메일·인스타) 하나만 있으면 된다. 방법이 없으면 전화로 본다(옛 폼 호환).
 */
public record CreateInquiryRequest(

        @NotNull(message = "문의 유형은 필수입니다")
        InquiryType type,

        @NotBlank(message = "업체·행사명은 필수입니다")
        @Size(max = 80, message = "업체·행사명은 80자 이하입니다")
        String organization,

        @NotBlank(message = "담당자 성함은 필수입니다")
        @Size(max = 40, message = "담당자 성함은 40자 이하입니다")
        String contactName,

        ContactMethod contactMethod,

        @Pattern(regexp = "^[0-9+\\-\\s()]{8,20}$", message = "연락처 형식이 올바르지 않습니다")
        String phone,

        @Email(message = "이메일 형식이 올바르지 않습니다")
        @Size(max = 120, message = "이메일은 120자 이하입니다")
        String email,

        // @ 와 주소를 뗀 아이디. 랜딩과 같은 규칙 — 마침표로 시작·끝나거나 연속되면 인스타가 받지 않는 아이디다
        @Pattern(regexp = "^(?!\\.)(?!.*\\.\\.)[a-z0-9._]{1,30}(?<!\\.)$", message = "인스타그램 아이디 형식이 올바르지 않습니다")
        String instagram,

        @Size(max = 40, message = "지역은 40자 이하입니다")
        String region,

        @Size(max = 80, message = "희망 시기는 80자 이하입니다")
        String preferredPeriod,

        @Size(max = 80, message = "규모는 80자 이하입니다")
        String scale,

        @Size(max = 200, message = "홈페이지·SNS 주소는 200자 이하입니다")
        String website,

        @Size(max = 500, message = "문의 내용은 500자 이하입니다")
        String message,

        Boolean privacyAgreed,

        Boolean marketingAgreed,

        @Valid
        Utm utm,

        @Size(max = 200, message = "landingPath 는 200자 이하입니다")
        String landingPath,

        String fax
) {
    @AssertTrue(message = "개인정보 수집·이용에 동의해야 합니다")
    public boolean isPrivacyConsented() {
        return Boolean.TRUE.equals(privacyAgreed);
    }

    /** 고른 연락 방법. 없으면 전화 — contactMethod 가 생기기 전 폼은 phone 만 보냈다. */
    public ContactMethod effectiveContactMethod() {
        return contactMethod == null ? ContactMethod.PHONE : contactMethod;
    }

    @AssertTrue(message = "선택한 연락 방법의 연락처를 입력해 주세요")
    public boolean isContactProvided() {
        String value = switch (effectiveContactMethod()) {
            case PHONE -> phone;
            case EMAIL -> email;
            case INSTAGRAM -> instagram;
        };
        return value != null && !value.isBlank();
    }

    /** 봇이 채운 숨은 칸. 사람 화면에는 없는 필드라 값이 있으면 사람이 아니다. */
    public boolean isHoneypotFilled() {
        return fax != null && !fax.isBlank();
    }

    public boolean marketingConsented() {
        return Boolean.TRUE.equals(marketingAgreed);
    }

    /** 광고 링크의 utm_* 다섯 값. 폼 페이지가 주소창에서 읽어 그대로 넘긴다. */
    public record Utm(
            @Size(max = 100) String source,
            @Size(max = 100) String medium,
            @Size(max = 100) String campaign,
            @Size(max = 100) String content,
            @Size(max = 100) String term
    ) {
    }
}
