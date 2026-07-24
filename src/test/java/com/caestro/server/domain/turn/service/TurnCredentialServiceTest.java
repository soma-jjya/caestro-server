package com.caestro.server.domain.turn.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.caestro.server.domain.turn.dto.response.TurnCredentialResponse;
import com.caestro.server.domain.turn.dto.response.TurnCredentialResponse.IceServer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TurnCredentialServiceTest {

    private static final String SECRET = "test-static-auth-secret";
    private static final String HOST = "1.2.3.4";
    private static final long TTL = 3600L;
    private static final long NOW_EPOCH = 1_000_000L;

    private TurnCredentialService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.ofEpochSecond(NOW_EPOCH), ZoneOffset.UTC);
        service = new TurnCredentialService(SECRET, HOST, TTL, fixedClock);
    }

    @Test
    @DisplayName("STUN(자격증명 없음)과 TURN(임시 자격증명 포함)을 순서대로 반환한다")
    void generate_returnsStunAndTurn() {
        TurnCredentialResponse res = service.generate(42L);

        assertThat(res.ttl()).isEqualTo(TTL);
        assertThat(res.iceServers()).hasSize(2);

        IceServer stun = res.iceServers().get(0);
        assertThat(stun.urls()).containsExactly("stun:1.2.3.4:3478");
        assertThat(stun.username()).isNull();
        assertThat(stun.credential()).isNull();

        IceServer turn = res.iceServers().get(1);
        assertThat(turn.urls()).containsExactly("turn:1.2.3.4:3478?transport=udp");
        assertThat(turn.username()).isNotNull();
        assertThat(turn.credential()).isNotNull();
    }

    @Test
    @DisplayName("username은 {만료시각}:{userId} 형식이다")
    void generate_usernameFormat() {
        TurnCredentialResponse res = service.generate(42L);

        long expectedExpiry = NOW_EPOCH + TTL; // 1_003_600
        assertThat(res.iceServers().get(1).username()).isEqualTo(expectedExpiry + ":42");
    }

    @Test
    @DisplayName("credential은 base64(HMAC-SHA1(secret, username))과 일치한다")
    void generate_credentialMatchesHmac() throws Exception {
        TurnCredentialResponse res = service.generate(42L);
        IceServer turn = res.iceServers().get(1);

        String expected = hmacSha1Base64(SECRET, turn.username());
        assertThat(turn.credential()).isEqualTo(expected);
    }

    private static String hmacSha1Base64(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
