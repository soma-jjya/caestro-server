package com.caestro.server.domain.subscription.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 앱이 Google Play 결제 직후(또는 구매 복원 때) 보내는 구매 토큰.
 * 사용자는 토큰의 JWT 로 정해지고, 구매가 그 계정 것인지는 서버가 Google 에 물어 확인한다.
 *
 * @param purchaseToken Purchase.getPurchaseToken()
 * @param productId     구독 상품 ID (생략하면 서버 설정값 peakpic_pro 로 본다)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GooglePlayPurchaseRequest(

        @NotBlank(message = "purchaseToken 이 필요합니다")
        @Size(max = 512, message = "purchaseToken 이 너무 깁니다")
        @Pattern(regexp = "[A-Za-z0-9._\\-]+", message = "purchaseToken 형식이 올바르지 않습니다")
        String purchaseToken,

        String productId
) {
}
