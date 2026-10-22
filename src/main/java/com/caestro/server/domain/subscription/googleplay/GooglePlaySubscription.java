package com.caestro.server.domain.subscription.googleplay;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Google Play Developer API purchases.subscriptionsv2.get 응답에서 권한 판단에 필요한 값만 추린 것.
 * https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2
 *
 * @param subscriptionState     SUBSCRIPTION_STATE_ACTIVE · IN_GRACE_PERIOD · CANCELED · ON_HOLD · PAUSED · EXPIRED · PENDING …
 * @param acknowledgementState  ACKNOWLEDGEMENT_STATE_PENDING 이면 3일 안에 승인해야 환불되지 않는다
 * @param linkedPurchaseToken   요금제 변경·재구독이면 이전 구매 토큰
 * @param obfuscatedAccountId   앱이 결제 때 넣은 계정 식별자(사용자 id 의 SHA-256 hex). 없을 수 있다
 */
public record GooglePlaySubscription(
        String subscriptionState,
        String acknowledgementState,
        String latestOrderId,
        String linkedPurchaseToken,
        String obfuscatedAccountId,
        Instant startTime,
        boolean testPurchase,
        List<LineItem> lineItems
) {

    public static final String STATE_ACTIVE = "SUBSCRIPTION_STATE_ACTIVE";
    public static final String STATE_IN_GRACE_PERIOD = "SUBSCRIPTION_STATE_IN_GRACE_PERIOD";
    public static final String STATE_CANCELED = "SUBSCRIPTION_STATE_CANCELED";
    public static final String STATE_PENDING = "SUBSCRIPTION_STATE_PENDING";
    public static final String ACK_PENDING = "ACKNOWLEDGEMENT_STATE_PENDING";

    /** 구독 상품 한 줄. 기본 요금제(basePlanId)와 이번 기간 만료 시각을 담는다. */
    public record LineItem(String productId, String basePlanId, Instant expiryTime, Boolean autoRenewEnabled,
                           String latestSuccessfulOrderId) {
    }

    /**
     * 권한이 살아 있는 상태인가. 해지(CANCELED)도 결제한 기간이 끝날 때까지는 이용할 수 있다.
     * 실제 이용 가능 여부는 여기에 더해 만료 시각이 지금 이후인지로 판단한다.
     */
    public boolean entitledState() {
        return STATE_ACTIVE.equals(subscriptionState)
                || STATE_IN_GRACE_PERIOD.equals(subscriptionState)
                || STATE_CANCELED.equals(subscriptionState);
    }

    public boolean pending() {
        return STATE_PENDING.equals(subscriptionState);
    }

    public boolean needsAcknowledgement() {
        return ACK_PENDING.equals(acknowledgementState);
    }

    /** 이 상품의 줄 중 만료가 가장 늦은 것. */
    public Optional<LineItem> lineFor(String productId) {
        return lineItems.stream()
                .filter(item -> productId.equals(item.productId()))
                .max(Comparator.comparing(item -> item.expiryTime() == null ? Instant.EPOCH : item.expiryTime()));
    }

    public static GooglePlaySubscription from(JsonNode root) {
        List<LineItem> items = new ArrayList<>();
        for (JsonNode item : root.path("lineItems")) {
            JsonNode autoRenewing = item.path("autoRenewingPlan");  // 선불 요금제(prepaidPlan)에는 없다
            items.add(new LineItem(
                    text(item, "productId"),
                    text(item.path("offerDetails"), "basePlanId"),
                    instant(item, "expiryTime"),
                    // proto3 JSON 은 false 를 생략한다: 필드가 없으면 갱신 안 함
                    autoRenewing.path("autoRenewEnabled").asBoolean(false),
                    text(item, "latestSuccessfulOrderId")
            ));
        }
        return new GooglePlaySubscription(
                text(root, "subscriptionState"),
                text(root, "acknowledgementState"),
                text(root, "latestOrderId"),
                text(root, "linkedPurchaseToken"),
                text(root.path("externalAccountIdentifiers"), "obfuscatedExternalAccountId"),
                instant(root, "startTime"),
                root.has("testPurchase"),
                List.copyOf(items)
        );
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isEmpty() ? null : value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
