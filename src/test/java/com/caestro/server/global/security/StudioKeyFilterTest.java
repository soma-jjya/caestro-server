package com.caestro.server.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** 디자이너 스튜디오 정적 키 필터: 카탈로그 경로에서 키가 맞을 때만 ROLE_STUDIO. */
class StudioKeyFilterTest {

    private static final String KEY = "test-studio-key";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void run(StudioKeyFilter filter, String method, String uri, String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (header != null) request.addHeader("X-Studio-Key", header);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    @DisplayName("올바른 키면 스튜디오 경로 요청을 ROLE_STUDIO 로 인증한다")
    void validKey_authenticates() throws Exception {
        run(new StudioKeyFilter(KEY), "POST", "/catalog/studio/frames", KEY);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_STUDIO");
    }

    @Test
    @DisplayName("공개 목록 경로에서도 키가 맞으면 인증한다 (?all=true 로 숨김을 보기 위해)")
    void validKey_onPublicList() throws Exception {
        run(new StudioKeyFilter(KEY), "GET", "/catalog/frames", KEY);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("틀린 키면 인증하지 않는다 (기존 체인이 403 처리)")
    void wrongKey_doesNotAuthenticate() throws Exception {
        run(new StudioKeyFilter(KEY), "POST", "/catalog/studio/frames", "nope");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("카탈로그 밖 경로에서는 맞는 키라도 인증하지 않는다")
    void otherPath_doesNotAuthenticate() throws Exception {
        run(new StudioKeyFilter(KEY), "GET", "/fills/ABCDEF", KEY);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("키가 설정되지 않았으면 필터가 완전히 무력화된다")
    void blankConfiguredKey_disabled() throws Exception {
        run(new StudioKeyFilter(""), "POST", "/catalog/studio/frames", "");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
