package com.caestro.server.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 운영 조회(/admin/ops/**, contracts/studio-ops.md)와 개발자 평가 열람(/admin/dev/**, 얼굴이 담긴 사진)을
 * 누가 언제 무엇을 봤는지 한 줄씩 남긴다(개인정보 접속 기록).
 * 보안 체인 안쪽(인가를 통과한 요청)에서만 돈다. 검색어 같은 값은 남기지 않고 쓴 파라미터 이름만 남긴다.
 */
@Slf4j
@Component
public class OpsAccessLogFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !(uri.startsWith(StudioKeyFilter.OPS_PATH_PREFIX) || uri.startsWith(StudioKeyFilter.DEV_PATH_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            List<String> params = request.getParameterMap().keySet().stream().sorted().toList();
            log.info("studio-ops access {} {} params={} status={} by={} ip={}", request.getMethod(), request.getRequestURI(),
                    params, response.getStatus(), auth == null ? "-" : auth.getName(), ClientIp.of(request));
        }
    }
}
