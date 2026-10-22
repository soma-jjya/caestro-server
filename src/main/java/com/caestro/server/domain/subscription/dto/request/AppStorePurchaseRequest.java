package com.caestro.server.domain.subscription.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 앱이 App Store 결제 직후(또는 앱 시작·구매 복원 때) 보내는 거래.
 * 사용자는 토큰의 JWT 로 정해지고, 거래가 그 계정 것인지는 서버가 Apple 에 물어 확인한다.
 *
 * @param signedTransaction StoreKit 2 의 VerificationResult.jwsRepresentation (서명된 거래 JWS). 서버는 거래 번호만 꺼낸다
 * @param productId         구독 상품 ID (peakpic_pro_monthly 등). 생략할 수 있다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppStorePurchaseRequest(

        @NotBlank(message = "signedTransaction 이 필요합니다")
        @Size(max = 16384, message = "signedTransaction 이 너무 깁니다")
        String signedTransaction,

        String productId
) {
}
