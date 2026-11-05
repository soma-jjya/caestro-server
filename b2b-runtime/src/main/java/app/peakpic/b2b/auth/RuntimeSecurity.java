package app.peakpic.b2b.auth;

import app.peakpic.b2b.RuntimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class RuntimeSecurity {
    @Bean SecurityFilterChain security(HttpSecurity http, GuestTokens tokens, RuntimeProperties properties, ObjectMapper mapper) throws Exception {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of("https://peakpic.app"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setExposedHeaders(List.of("Retry-After", "X-RateLimit-Scope"));
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**", cors);
        http.csrf(c -> c.disable()).cors(c -> c.configurationSource(source))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x) -> error(r, mapper, 401, "게스트 인증이 필요합니다."))
                        .accessDeniedHandler((q,r,x) -> error(r, mapper, 403, "접근 권한이 없습니다.")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/", "/b2b/images/*", "/catalog/images/*", "/fills/*/images/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/guest", "/auth/refresh").permitAll()
                        .requestMatchers("/admin/**", "/actuator/prometheus").hasRole("OPERATOR")
                        .requestMatchers("/fills/**").hasRole("GUEST")
                        .anyRequest().denyAll())
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                            throws IOException, ServletException {
                        String path = request.getRequestURI().substring(request.getContextPath().length());
                        if (path.startsWith("/admin/") || path.equals("/actuator/prometheus")) {
                            String key = request.getHeader("X-B2B-Admin-Key");
                            if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                                    properties.adminKey().getBytes(StandardCharsets.UTF_8))) {
                                error(response, mapper, 403, "운영자 키가 올바르지 않습니다."); return;
                            }
                            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                                    "operator", null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
                        } else {
                            String authorization = request.getHeader("Authorization");
                            if (authorization != null) {
                                try {
                                    if (!authorization.startsWith("Bearer ")) throw new IllegalArgumentException();
                                    long id = tokens.verify(authorization.substring(7));
                                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                                            id, null, List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));
                                } catch (RuntimeException invalid) {
                                    error(response, mapper, 401, "게스트 인증이 올바르지 않거나 만료되었습니다."); return;
                                }
                            }
                        }
                        chain.doFilter(request, response);
                    }
                }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    private static void error(HttpServletResponse response, ObjectMapper mapper, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), Map.of("status", status, "message", message));
    }
}
