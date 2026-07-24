package com.caestro.server.domain.subscription.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Positive;

/**
 * 구독 생성 요청.
 * user_id는 인증 토큰에서 추출하므로 요청 바디로 받지 않으며, 임의로 넣어도 무시된다(ignoreUnknown).
 * 실제 PG 결제 연동은 이번 스코프 밖이며, 결제 성공을 가정한 상태 기록용이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateSubscriptionRequest(

        String plan,

        @Positive(message = "duration은 양의 정수(일)여야 합니다")
        Integer duration
) {
}
