package com.caestro.server.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** 지표 수집기 정적 토큰 인증 필터 검증 (#82). */
class MetricsTokenFilterTest {

    private static final String TOKEN = "test-metrics-token";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void run(MetricsTokenFilter filter, String uri, String authHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (authHeader != null) request.addHeader("Authorization", authHeader);
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    @DisplayName("올바른 토큰이면 지표 경로 요청을 인증한다")
    void validToken_authenticates() throws Exception {
        run(new MetricsTokenFilter(TOKEN), "/actuator/prometheus", "Bearer " + TOKEN);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("metrics-scraper");
    }

    @Test
    @DisplayName("잘못된 토큰이면 인증하지 않는다 (기존 체인이 403 처리)")
    void wrongToken_doesNotAuthenticate() throws Exception {
        run(new MetricsTokenFilter(TOKEN), "/actuator/prometheus", "Bearer wrong-token");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("지표 경로가 아니면 올바른 토큰이라도 인증하지 않는다")
    void otherPath_doesNotAuthenticate() throws Exception {
        run(new MetricsTokenFilter(TOKEN), "/shots", "Bearer " + TOKEN);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("토큰이 설정되지 않았으면 필터가 완전히 무력화된다")
    void blankConfiguredToken_disabled() throws Exception {
        run(new MetricsTokenFilter(""), "/actuator/prometheus", "Bearer ");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
