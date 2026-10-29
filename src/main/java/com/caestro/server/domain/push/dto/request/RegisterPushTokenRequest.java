package com.caestro.server.domain.push.dto.request;

import com.caestro.server.domain.push.enums.PushPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterPushTokenRequest(

        @NotNull(message = "platform은 필수입니다")
        PushPlatform platform,

        @NotBlank(message = "token은 필수입니다")
        @Size(max = 512)
        String token
) {
}
