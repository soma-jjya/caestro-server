package com.caestro.server.domain.subscription.appstore;

import static com.caestro.server.domain.subscription.appstore.AppStoreFixtures.jws;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppStoreSubscriptionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String response(int status, String productId, long expires, String extra) throws Exception {
        String transaction = """
                {"originalTransactionId":"2000000111","transactionId":"2000000999","productId":"%s",
                 "bundleId":"soma.project.caestro","originalPurchaseDate":1761000000000,"expiresDate":%d,
                 "appAccountToken":"73475cb4-0a56-4e8d-a8a0-45ced110137e","environment":"Sandbox"%s}
                """.formatted(productId, expires, extra);
        String renewal = "{\"autoRenewStatus\":0,\"autoRenewProductId\":\"" + productId + "\"}";
        return """
                {"environment":"Sandbox","bundleId":"soma.project.caestro","data":[{"subscriptionGroupIdentifier":"21500001",
                 "lastTransactions":[{"originalTransactionId":"2000000111","status":%d,
                   "signedTransactionInfo":"%s","signedRenewalInfo":"%s"}]}]}
                """.formatted(status, jws(transaction), jws(renewal));
    }

    @Test
    @DisplayName("상태 응답에서 우리 상품의 구독 한 줄을 읽는다: 번호·상품·만료·계정 토큰·자동 갱신")
    void readsTheSubscription() throws Exception {
        AppStoreSubscription s = AppStoreSubscription.from(
                mapper.readTree(response(1, "peakpic_pro_monthly", 1_800_000_000_000L, "")), "peakpic_pro_", mapper)
                .orElseThrow();
        assertThat(s.originalTransactionId()).isEqualTo("2000000111");
        assertThat(s.transactionId()).isEqualTo("2000000999");
        assertThat(s.productId()).isEqualTo("peakpic_pro_monthly");
        assertThat(s.bundleId()).isEqualTo("soma.project.caestro");
        assertThat(s.expiresDate()).isEqualTo(Instant.ofEpochMilli(1_800_000_000_000L));
        assertThat(s.appAccountToken()).isEqualTo("73475cb4-0a56-4e8d-a8a0-45ced110137e");
        assertThat(s.autoRenewing()).isFalse();
        assertThat(s.environment()).isEqualTo("Sandbox");
        assertThat(s.entitledState()).isTrue();
        assertThat(s.stateName()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("결제 유예는 이용 가능, 결제 재시도·만료는 아니다. 환불(5)이나 revocationDate 는 철회")
    void states() throws Exception {
        assertThat(read(4, "").entitledState()).isTrue();
        assertThat(read(3, "").entitledState()).isFalse();
        assertThat(read(2, "").entitledState()).isFalse();
        assertThat(read(5, "").revoked()).isTrue();
        AppStoreSubscription refunded = read(1, ",\"revocationDate\":1761500000000");
        assertThat(refunded.revoked()).isTrue();
        assertThat(refunded.entitledState()).isFalse();
    }

    @Test
    @DisplayName("다른 상품의 구독이나 읽을 수 없는 응답은 없는 것으로 본다")
    void ignoresOthers() throws Exception {
        assertThat(AppStoreSubscription.from(
                mapper.readTree(response(1, "other_product", 1L, "")), "peakpic_pro_", mapper)).isEmpty();
        assertThat(AppStoreSubscription.from(mapper.readTree("{}"), "peakpic_pro_", mapper)).isEmpty();
        assertThat(AppStoreSubscription.AppStoreJws.payload("not-a-jws", mapper)).isNull();
    }

    private AppStoreSubscription read(int status, String extra) throws Exception {
        return AppStoreSubscription.from(
                mapper.readTree(response(status, "peakpic_pro_yearly", 1_800_000_000_000L, extra)), "peakpic_pro_", mapper)
                .orElseThrow();
    }
}
