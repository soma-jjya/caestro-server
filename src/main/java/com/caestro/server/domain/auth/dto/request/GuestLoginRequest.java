package com.caestro.server.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record GuestLoginRequest(

    @NotBlank(message = "디바이스 ID는 필수입니다")
    String deviceId
) {
}
