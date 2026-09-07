package com.caestro.server.global.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    /**
     * 모든 외부 API 호출(카카오·구글·애플)이 공유하는 WebClient 공통 설정 (#125).
     * 타임아웃 상한이 없으면 외부 서버가 침묵할 때 톰캣 스레드가 무한 대기하고,
     * 모놀리스는 스레드풀을 전 기능이 공유하므로 로그인 장애가 시그널링까지 전염된다.
     * 값 근거: 정상 왕복(수백 ms) 분포의 바깥 + 실패 비용이 낮은 best-effort 경로 — 운영 실측 후 조정.
     */
    @Bean
    public WebClient.Builder webClientBuilder(
            @Value("${external.http.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${external.http.response-timeout-ms:3000}") int responseTimeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs));
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
