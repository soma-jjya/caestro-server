package com.caestro.server.domain.subscription.appstore;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 테스트용 JWS: 헤더·서명은 자리만 채우고, 가운데(payload)만 의미가 있다. */
public final class AppStoreFixtures {

    private AppStoreFixtures() {
    }

    public static String jws(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
                + ".c2ln";
    }
}
