package com.caestro.server.domain.subscription.googleplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GooglePlaySubscriptionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("subscriptionsv2 응답에서 상태·만료·요금제·계정 식별자를 읽는다")
    void parsesSubscriptionV2() throws Exception {
        String json = """
                {
                  "kind": "androidpublisher#subscriptionPurchaseV2",
                  "startTime": "2026-10-22T03:00:00.123Z",
                  "regionCode": "KR",
                  "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
                  "latestOrderId": "GPA.3333-4444-5555-66666",
                  "acknowledgementState": "ACKNOWLEDGEMENT_STATE_PENDING",
                  "externalAccountIdentifiers": { "obfuscatedExternalAccountId": "abc123" },
                  "testPurchase": {},
                  "lineItems": [
                    {
                      "productId": "peakpic_pro",
                      "expiryTime": "2026-11-22T03:00:00Z",
                      "autoRenewingPlan": { "autoRenewEnabled": true },
                      "offerDetails": { "basePlanId": "monthly" },
                      "latestSuccessfulOrderId": "GPA.3333-4444-5555-66666"
                    }
                  ]
                }
                """;

        GooglePlaySubscription s = GooglePlaySubscription.from(objectMapper.readTree(json));

        assertThat(s.subscriptionState()).isEqualTo(GooglePlaySubscription.STATE_ACTIVE);
        assertThat(s.entitledState()).isTrue();
        assertThat(s.needsAcknowledgement()).isTrue();
        assertThat(s.obfuscatedAccountId()).isEqualTo("abc123");
        assertThat(s.testPurchase()).isTrue();
        assertThat(s.startTime()).isEqualTo(Instant.parse("2026-10-22T03:00:00.123Z"));
        GooglePlaySubscription.LineItem line = s.lineFor("peakpic_pro").orElseThrow();
        assertThat(line.basePlanId()).isEqualTo("monthly");
        assertThat(line.expiryTime()).isEqualTo(Instant.parse("2026-11-22T03:00:00Z"));
        assertThat(line.autoRenewEnabled()).isTrue();
        assertThat(s.lineFor("other")).isEmpty();
    }

    @Test
    @DisplayName("생략된 false(자동 갱신 꺼짐)와 없는 필드는 false·null 로 읽는다")
    void omittedFieldsDefault() throws Exception {
        String json = """
                {
                  "subscriptionState": "SUBSCRIPTION_STATE_CANCELED",
                  "lineItems": [ { "productId": "peakpic_pro", "expiryTime": "2026-11-22T03:00:00Z", "autoRenewingPlan": {} } ]
                }
                """;

        GooglePlaySubscription s = GooglePlaySubscription.from(objectMapper.readTree(json));

        assertThat(s.entitledState()).isTrue();
        assertThat(s.obfuscatedAccountId()).isNull();
        assertThat(s.linkedPurchaseToken()).isNull();
        assertThat(s.testPurchase()).isFalse();
        assertThat(s.lineFor("peakpic_pro").orElseThrow().autoRenewEnabled()).isFalse();
        assertThat(s.lineFor("peakpic_pro").orElseThrow().basePlanId()).isNull();
    }

    @Test
    @DisplayName("보류·만료·결제 대기는 이용 불가 상태")
    void nonEntitledStates() throws Exception {
        for (String state : new String[]{"SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_PAUSED",
                "SUBSCRIPTION_STATE_EXPIRED", "SUBSCRIPTION_STATE_PENDING"}) {
            GooglePlaySubscription s = GooglePlaySubscription.from(
                    objectMapper.readTree("{\"subscriptionState\":\"" + state + "\"}"));
            assertThat(s.entitledState()).as(state).isFalse();
        }
    }
}
