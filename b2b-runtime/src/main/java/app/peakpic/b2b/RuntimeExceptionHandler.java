package app.peakpic.b2b;

import com.caestro.server.global.exception.error.ErrorDto;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(-1)
public class RuntimeExceptionHandler {
    @ExceptionHandler(RuntimeFailure.class)
    ResponseEntity<ErrorDto> handle(RuntimeFailure failure) {
        if (failure instanceof app.peakpic.b2b.auth.GuestAdmission.Rejected rejected) {
            return ResponseEntity.status(429).header("Retry-After", Integer.toString(rejected.retryAfter()))
                    .header("X-RateLimit-Scope", rejected.scope()).cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(new ErrorDto(429, rejected.getMessage()));
        }
        return ResponseEntity.status(failure.status()).body(new ErrorDto(failure.status(), failure.getMessage()));
    }
}
