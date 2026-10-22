package com.caestro.server.domain.subscription.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Cloud Pub/Sub 푸시 구독이 보내는 봉투. message.data 가 Google Play 실시간 개발자 알림(RTDN) JSON 의 base64 다.
 * https://developer.android.com/google/play/billing/rtdn-reference
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PubSubPushRequest(Message message, String subscription) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String data, String messageId, Map<String, String> attributes) {
    }
}
