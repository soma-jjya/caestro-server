package com.caestro.server.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientPlatformTest {

    @Test
    @DisplayName("User-Agent 로 앱을 가린다: 안드로이드 OkHttp, iOS URLSession, 브라우저는 웹, 그 밖은 null")
    void fromUserAgent() {
        assertThat(ClientPlatform.fromUserAgent("okhttp/4.12.0")).isEqualTo("ANDROID");
        assertThat(ClientPlatform.fromUserAgent("peakpic/10 CFNetwork/3826.500.111 Darwin/24.4.0")).isEqualTo("IOS");
        assertThat(ClientPlatform.fromUserAgent(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"))
                .isEqualTo("WEB");
        assertThat(ClientPlatform.fromUserAgent(
                "Mozilla/5.0 (Linux; Android 16; SM-S938N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36"))
                .isEqualTo("WEB");
        assertThat(ClientPlatform.fromUserAgent("curl/8.7.1")).isNull();
        assertThat(ClientPlatform.fromUserAgent(null)).isNull();
        assertThat(ClientPlatform.fromUserAgent(" ")).isNull();
    }
}
