package com.caestro.server.global.config;

import com.caestro.server.global.jwt.JwtAuthenticationFilter;
import com.caestro.server.global.security.MetricsTokenFilter;
import com.caestro.server.global.security.StudioKeyFilter;
import com.caestro.server.global.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
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

    // 디자이너 스튜디오(peakpic.app/studio) 정적 키. 미설정(빈 값)이면 스튜디오 쓰기 전체가 403 (contracts/catalog-studio.md)
    @Value("${catalog.studio-key:}")
    private String studioKey;

    // 웹에서 채우기(peakpic.app)가 브라우저에서 API를 직접 부른다. 쉼표 구분; 로컬 개발은 프로파일에서 덧붙인다.
    @Value("${cors.allowed-origins:https://peakpic.app,https://www.peakpic.app}")
    private String corsAllowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 함께 채우기 이미지: 세션 코드 + 22자 난수 id로만 열리므로 인증 없이 내준다 (앱·웹이 <img>/OkHttp로 그대로 그림)
                        .requestMatchers(HttpMethod.GET, "/fills/*/images/*").permitAll()
                        // 카탈로그(디자이너가 올린 프레임·배너) 읽기: 앱·웹이 로그인 전에도 내려받는다
                        .requestMatchers(HttpMethod.GET, "/catalog/frames", "/catalog/frames/*", "/catalog/banners", "/catalog/pose", "/catalog/images/*").permitAll()
                        // Google Play 실시간 알림(Pub/Sub 푸시): JWT 가 없다. 컨트롤러가 URL 의 ?token= 으로 확인한다
                        .requestMatchers(HttpMethod.POST, "/subscriptions/google-play/notifications").permitAll()
                        // App Store 서버 알림(V2): JWT 가 없다. 컨트롤러가 URL 의 ?token= 으로 확인한다
                        .requestMatchers(HttpMethod.POST, "/subscriptions/app-store/notifications").permitAll()
                        // 스튜디오 쓰기: X-Studio-Key(ROLE_STUDIO) 또는 관리자 JWT
                        .requestMatchers("/catalog/studio/**").hasAnyRole("STUDIO", "ADMIN")
                        // B2B 운영자는 같은 서버 전용 키를 사용한다. 공개 프로젝트와 이미지 읽기만 비인증이다.
                        .requestMatchers("/b2b/admin/**").hasAnyRole("STUDIO", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/b2b/projects/*", "/b2b/images/*").permitAll()
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
                .addFilterBefore(new MetricsTokenFilter(metricsToken), JwtAuthenticationFilter.class)
                // 스튜디오 키도 JWT 가 아니다 — JWT 필터 앞에서 ROLE_STUDIO 를 세운다
                .addFilterBefore(new StudioKeyFilter(studioKey), JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-None-Match", "X-Studio-Key"));
        config.setExposedHeaders(List.of("ETag"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
