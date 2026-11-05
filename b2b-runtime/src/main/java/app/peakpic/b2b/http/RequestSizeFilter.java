package app.peakpic.b2b.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound both Content-Length and chunked bodies before MVC allocates byte[]. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestSizeFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    public RequestSizeFilter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!request.getMethod().equals("POST") && !request.getMethod().equals("PUT")) { chain.doFilter(request, response); return; }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        int limit = path.equals("/admin/images") || path.matches("/fills/[^/]+/slots/[0-3]") ? 3145728 : 65536;
        if (request.getContentLengthLong() > limit) { reject(response); return; }
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) { reject(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    public int read() { return input.read(); }
                    public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                    public boolean isFinished() { return input.available() == 0; }
                    public boolean isReady() { return true; }
                    public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("synchronous requests only"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
        }, response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413); response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), Map.of("status", 413, "message", "요청 크기가 허용 범위를 초과했습니다."));
    }
}
