package com.caestro.server.global.config;

import com.caestro.server.global.jwt.JwtAuthenticationFilter;
import com.caestro.server.global.security.MetricsTokenFilter;
import com.caestro.server.global.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final RedisTemplate<String, String> redisTemplate;

    // 지표 수집기용 정적 토큰. 미설정(빈 값)이면 필터가 무력화되어 기존 JWT 인증만 적용된다.
    @Value("${metrics.token:}")
    private String metricsToken;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/auth/**",
                                "/signaling",
                                "/signaling/**",
                                "/api-docs",
                                "/api-docs/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                // 헬스체크: 무인증 접근 (LB/배포 파이프라인용). liveness/readiness 포함
                                "/actuator/health",
                                "/actuator/health/**",
                                // info: prod에선 미노출(exposure=health)이라 404, dev에서만 조회됨
                                "/actuator/info"
                        ).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider, redisTemplate), UsernamePasswordAuthenticationFilter.class)
                // 수집기 토큰 인증을 JWT 필터보다 먼저 수행 (지표 토큰은 JWT가 아니므로 JWT 필터는 무시하고 통과시킨다)
                .addFilterBefore(new MetricsTokenFilter(metricsToken), JwtAuthenticationFilter.class);

        return http.build();
    }
}
