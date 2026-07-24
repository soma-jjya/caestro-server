package com.caestro.server.domain.auth.dto.response;

public record TokenResponse(
        String accessToken,
        String refreshToken
) {
}
