package com.caestro.server.global.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * ShedLock 설정.
 * 다중 인스턴스 환경에서 @Scheduled 배치가 중복 실행되지 않도록,
 * 기존 Redis를 락 저장소로 사용해 "여러 인스턴스 중 하나만" 실행되게 한다.
 *
 * defaultLockAtMostFor: 배치가 비정상 종료해도 이 시간 후 락이 자동 해제되어 데드락을 방지한다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ShedLockConfig {

    // 락 키 접두사(환경 구분). 여러 서비스가 같은 Redis를 써도 키가 충돌하지 않게 한다.
    private static final String ENV = "peakpic";

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory redisConnectionFactory) {
        return new RedisLockProvider(redisConnectionFactory, ENV);
    }
}
