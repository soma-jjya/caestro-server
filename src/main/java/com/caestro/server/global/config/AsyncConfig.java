package com.caestro.server.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * @Async 활성화 (#206). 실행기는 Spring Boot 기본(applicationTaskExecutor, spring.task.execution.*)을 그대로 쓴다 —
 * 지표(executor 태그)가 자동으로 붙고, 따로 빈을 두면 기본 실행기가 사라져 MVC 비동기 처리에도 영향이 가기 때문이다.
 * 지금 쓰는 곳은 네 칸 완성 푸시뿐이다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
