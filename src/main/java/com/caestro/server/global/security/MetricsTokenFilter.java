package com.caestro.server.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 지표 수집기(Prometheus) 전용 정적 토큰 인증 필터.
 * JWT를 발급받을 수 없는 수집기가 /actuator/prometheus 를 스크레이프할 수 있도록,
 * 해당 경로에 한해 설정된 토큰(metrics.token)과 일치하는 Bearer 헤더를 인증으로 인정한다.
 * 토큰이 설정되지 않았거나 불일치하면 아무것도 하지 않는다 → 기존 체인(JWT 또는 403)이 그대로 적용된다.
 */
public class MetricsTokenFilter extends OncePerRequestFilter {

    private static final String METRICS_PATH = "/actuator/prometheus";
    private static final String BEARER_PREFIX = "Bearer ";

    private final String metricsToken;

    public MetricsTokenFilter(String metricsToken) {
        this.metricsToken = metricsToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isValidMetricsRequest(request)) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "metrics-scraper", null, List.of(new SimpleGrantedAuthority("ROLE_METRICS")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    private boolean isValidMetricsRequest(HttpServletRequest request) {
        if (metricsToken == null || metricsToken.isBlank()) return false;   // 미설정 시 완전 무력화
        if (!METRICS_PATH.equals(request.getRequestURI())) return false;

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) return false;

        // 타이밍 공격 방지를 위한 상수 시간 비교
        byte[] provided = header.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        byte[] expected = metricsToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expected);
    }
}
