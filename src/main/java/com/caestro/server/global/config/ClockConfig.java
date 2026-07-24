package com.caestro.server.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /**
     * 시간 의존 로직(일일 사용량 날짜 계산 등)을 테스트 가능하게 하기 위해 Clock을 빈으로 제공한다.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
