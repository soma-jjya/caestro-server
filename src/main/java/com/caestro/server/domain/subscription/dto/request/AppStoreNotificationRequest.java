package com.caestro.server.domain.subscription.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * App Store Server Notifications V2 본문: {"signedPayload": "<JWS>"}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppStoreNotificationRequest(String signedPayload) {
}
