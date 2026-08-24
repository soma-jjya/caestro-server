package com.caestro.server.global.config;

import com.caestro.server.domain.signaling.service.SignalingPubSubSubscriber;
import com.caestro.server.domain.signaling.service.SignalingRelaySender;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(new StringRedisSerializer());
        return redisTemplate;
    }

    /**
     * Lettuce 명령 단위 지연 계측 (#89).
     * relay 핫패스의 지연을 "왕복 몇 회 × 회당 몇 ms"로 분해하기 위한 근거 지표.
     * 부트가 자동 구성하는 ClientResources에 recorder만 끼워 넣는다 (커넥션 설정은 그대로).
     */
    @Bean
    public ClientResourcesBuilderCustomizer lettuceMetricsCustomizer(MeterRegistry meterRegistry) {
        MicrometerOptions options = MicrometerOptions.builder()
                // 버킷 히스토그램 발행: Prometheus에서 인스턴스 횡단 분위수 집계(histogram_quantile)용
                .histogram(true)
                // LAN Redis 왕복은 서브 ms — 하한을 100µs로 내려 그 구간이 뭉개지지 않게 한다
                .minLatency(Duration.ofNanos(100_000)) // 100µs
                .maxLatency(Duration.ofSeconds(5))
                .build();
        return builder -> builder.commandLatencyRecorder(
                new MicrometerCommandLatencyRecorder(meterRegistry, options));
    }

    /**
     * 크로스 인스턴스 시그널링 중계용 Redis Pub/Sub 구독 컨테이너.
     * 앱 기동 시 중계 채널을 구독하여, 다른 인스턴스가 발행한 메시지를 수신한다.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory redisConnectionFactory,
            SignalingPubSubSubscriber signalingPubSubSubscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(signalingPubSubSubscriber, new ChannelTopic(SignalingRelaySender.CHANNEL));
        return container;
    }
}
