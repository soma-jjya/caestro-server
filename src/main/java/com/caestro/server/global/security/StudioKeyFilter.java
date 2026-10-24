package com.caestro.server.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 디자이너 스튜디오(peakpic.app/studio) 전용 정적 키 인증 필터 (contracts/catalog-studio.md).
 * JWT 를 발급받지 않는 브라우저 페이지가 /catalog/** 를 부를 때, 헤더 X-Studio-Key 가 설정값(catalog.studio-key)과
 * 일치하면 ROLE_STUDIO 로 인증한다. 키가 설정되지 않았거나 불일치하면 아무것도 하지 않는다 → 기존 체인(JWT 또는 403).
 * /catalog/** 및 /b2b/admin/** 에서만 인증한다. B2B 참여·기존 사용자 API에는 이 키를 사용할 수 없다.
 */
public class StudioKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Studio-Key";
    private static final String CATALOG_PATH_PREFIX = "/catalog/";

    private final String studioKey;

    public StudioKeyFilter(String studioKey) {
        this.studioKey = studioKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isValidStudioRequest(request)) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "studio", null, List.of(new SimpleGrantedAuthority("ROLE_STUDIO")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    private boolean isValidStudioRequest(HttpServletRequest request) {
        if (studioKey == null || studioKey.isBlank()) return false;   // 미설정 시 완전 무력화
        String uri = request.getRequestURI();
        if (uri == null || !(uri.startsWith(CATALOG_PATH_PREFIX) || uri.startsWith("/b2b/admin/"))) return false;

        String provided = request.getHeader(HEADER);
        if (provided == null || provided.isEmpty()) return false;

        // 타이밍 공격 방지를 위한 상수 시간 비교
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), studioKey.getBytes(StandardCharsets.UTF_8));
    }
}
