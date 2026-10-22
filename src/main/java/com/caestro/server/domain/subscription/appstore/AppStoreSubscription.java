package com.caestro.server.domain.subscription.appstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * App Store Server API "Get All Subscription Statuses" 응답에서 권한 판단에 필요한 값만 추린 것.
 * https://developer.apple.com/documentation/appstoreserverapi/get_all_subscription_statuses
 *
 * <p>응답의 signedTransactionInfo · signedRenewalInfo 는 Apple 이 서명한 JWS 다. 이 값은 서버가 TLS 로 Apple 에서 직접
 * 받아 온 것이라 그대로 읽는다(앱이 보낸 JWS 는 거래 번호를 꺼내는 데만 쓰고 믿지 않는다).
 *
 * @param status                 1 구독 중 · 2 만료 · 3 결제 재시도 · 4 결제 유예 · 5 철회(환불)
 * @param originalTransactionId  구독 한 줄의 고정 번호. 요금제를 바꾸거나 갱신해도 같다 — 계정에 묶는 키
 * @param transactionId          가장 최근 거래 번호 (환불·문의 대조용)
 * @param productId              peakpic_pro_yearly · peakpic_pro_monthly · peakpic_pro_weekly
 * @param appAccountToken        앱이 결제 때 넣은 계정 식별자(UUID). 없을 수 있다
 * @param autoRenewing           다음 결제일에 자동 갱신되는지. false 면 해지 예약
 */
public record AppStoreSubscription(
        int status,
        String originalTransactionId,
        String transactionId,
        String productId,
        String bundleId,
        Instant originalPurchaseDate,
        Instant expiresDate,
        Instant revocationDate,
        String appAccountToken,
        Boolean autoRenewing,
        String environment
) {

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_EXPIRED = 2;
    public static final int STATUS_BILLING_RETRY = 3;
    public static final int STATUS_GRACE_PERIOD = 4;
    public static final int STATUS_REVOKED = 5;

    /**
     * 권한이 살아 있는 상태인가: 구독 중이거나 결제 유예 기간. 해지 예약도 구독 중(1)으로 오고, 결제한 기간이 끝나면 만료(2)가 된다.
     * 실제 이용 가능 여부는 여기에 더해 만료 시각이 지금 이후인지로 판단한다.
     */
    public boolean entitledState() {
        return revocationDate == null && (status == STATUS_ACTIVE || status == STATUS_GRACE_PERIOD);
    }

    public boolean revoked() {
        return status == STATUS_REVOKED || revocationDate != null;
    }

    /** status 를 사람이 읽는 이름으로: 행의 storeState 에 남긴다. */
    public String stateName() {
        return switch (status) {
            case STATUS_ACTIVE -> "ACTIVE";
            case STATUS_EXPIRED -> "EXPIRED";
            case STATUS_BILLING_RETRY -> "BILLING_RETRY";
            case STATUS_GRACE_PERIOD -> "BILLING_GRACE_PERIOD";
            case STATUS_REVOKED -> "REVOKED";
            default -> "UNKNOWN_" + status;
        };
    }

    /**
     * 응답에서 이 상품들의 구독 한 줄을 찾는다(구독 그룹 하나에 요금제 셋). 여러 줄이면 만료가 가장 늦은 것.
     *
     * @param productPrefix 우리 상품 id 의 앞부분 (peakpic_pro_)
     */
    public static Optional<AppStoreSubscription> from(JsonNode response, String productPrefix, ObjectMapper mapper) {
        AppStoreSubscription best = null;
        for (JsonNode group : response.path("data")) {
            for (JsonNode last : group.path("lastTransactions")) {
                JsonNode transaction = AppStoreJws.payload(last.path("signedTransactionInfo").asText(null), mapper);
                if (transaction == null || !transaction.path("productId").asText("").startsWith(productPrefix)) {
                    continue;
                }
                JsonNode renewal = AppStoreJws.payload(last.path("signedRenewalInfo").asText(null), mapper);
                AppStoreSubscription candidate = new AppStoreSubscription(
                        last.path("status").asInt(0),
                        textOr(transaction, "originalTransactionId", last.path("originalTransactionId").asText(null)),
                        textOr(transaction, "transactionId", null),
                        transaction.path("productId").asText(),
                        textOr(transaction, "bundleId", null),
                        millis(transaction, "originalPurchaseDate"),
                        millis(transaction, "expiresDate"),
                        millis(transaction, "revocationDate"),
                        textOr(transaction, "appAccountToken", null),
                        renewal == null || !renewal.has("autoRenewStatus") ? null : renewal.path("autoRenewStatus").asInt() == 1,
                        textOr(transaction, "environment", response.path("environment").asText(null))
                );
                if (best == null || later(candidate.expiresDate, best.expiresDate)) {
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean later(Instant a, Instant b) {
        return a != null && (b == null || a.isAfter(b));
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static Instant millis(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : Instant.ofEpochMilli(value.asLong());
    }

    /** JWS(header.payload.signature)의 가운데를 읽는다. 서명 확인은 하지 않는다 — 출처를 믿을 수 있는 곳에서만 쓴다. */
    public static final class AppStoreJws {

        private AppStoreJws() {
        }

        public static JsonNode payload(String jws, ObjectMapper mapper) {
            if (jws == null || jws.isBlank()) {
                return null;
            }
            String[] parts = jws.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            try {
                return mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            } catch (Exception e) {
                return null;
            }
        }
    }
}
