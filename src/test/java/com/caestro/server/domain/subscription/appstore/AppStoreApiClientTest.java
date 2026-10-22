package com.caestro.server.domain.subscription.appstore;

import static com.caestro.server.domain.subscription.appstore.AppStoreFixtures.jws;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AppStoreApiClientTest {

    private static final String PRODUCTION = "https://api.storekit.itunes.apple.com/inApps/v1/subscriptions/2000000111";
    private static final String SANDBOX = "https://api.storekit-sandbox.itunes.apple.com/inApps/v1/subscriptions/2000000111";

    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private AppStoreApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AppStoreApiClient("soma.project.caestro", "issuer-1", "KEY123", pem, "auto", "peakpic_pro_",
                builder.build(), mapper);
    }

    private String statuses() {
        String transaction = jws("{\"originalTransactionId\":\"2000000111\",\"transactionId\":\"2000000999\","
                + "\"productId\":\"peakpic_pro_weekly\",\"bundleId\":\"soma.project.caestro\",\"expiresDate\":1800000000000}");
        return "{\"data\":[{\"lastTransactions\":[{\"status\":1,\"signedTransactionInfo\":\"" + transaction + "\"}]}]}";
    }

    @Test
    @DisplayName("요청 토큰: ES256, kid, 발급자·대상(appstoreconnect-v1)·번들 id 를 담는다")
    void bearerCarriesTheClaimsAppleAsksFor() {
        String[] parts = client.bearer().split("\\.");
        JsonNode header = AppStoreSubscription.AppStoreJws.payload("x." + parts[0] + ".y", mapper);
        JsonNode claims = AppStoreSubscription.AppStoreJws.payload("x." + parts[1] + ".y", mapper);
        assertThat(header.path("alg").asText()).isEqualTo("ES256");
        assertThat(header.path("kid").asText()).isEqualTo("KEY123");
        assertThat(header.path("typ").asText()).isEqualTo("JWT");
        assertThat(claims.path("iss").asText()).isEqualTo("issuer-1");
        assertThat(claims.path("aud").toString()).contains(AppStoreApiClient.AUDIENCE);
        assertThat(claims.path("bid").asText()).isEqualTo("soma.project.caestro");
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isLessThanOrEqualTo(3600);
    }

    @Test
    @DisplayName("운영에 먼저 묻고, 모르는 거래면 샌드박스(TestFlight·개발 빌드)에 묻는다")
    void productionThenSandbox() {
        server.expect(requestTo(PRODUCTION)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(SANDBOX))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Bearer ")))
                .andRespond(withSuccess(statuses(), MediaType.APPLICATION_JSON));

        AppStoreSubscription found = client.getSubscription("2000000111").orElseThrow();

        assertThat(found.productId()).isEqualTo("peakpic_pro_weekly");
        server.verify();
    }

    @Test
    @DisplayName("어디에도 없는 거래는 빈 값, Apple 장애는 AppStoreUnavailableException")
    void unknownAndUnavailable() {
        server.expect(requestTo(PRODUCTION)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(SANDBOX)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client.getSubscription("2000000111")).isEmpty();
        server.verify();

        server.reset();
        server.expect(requestTo(PRODUCTION)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.getSubscription("2000000111")).isInstanceOf(AppStoreUnavailableException.class);
    }

    @Test
    @DisplayName("키가 없으면 확인을 할 수 없다고 말한다(서버는 정상 기동)")
    void unconfigured() {
        AppStoreApiClient empty = new AppStoreApiClient("soma.project.caestro", "", "", "", "auto", "peakpic_pro_",
                RestClient.create(), mapper);
        assertThat(empty.configured()).isFalse();
        assertThatThrownBy(empty::bearer).isInstanceOf(AppStoreUnavailableException.class);
    }
}
