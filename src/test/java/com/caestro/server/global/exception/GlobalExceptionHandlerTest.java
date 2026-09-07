package com.caestro.server.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.caestro.server.global.exception.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

/**
 * 예외 로깅 정책 (#131): 5xx(서버 측 이상)는 흔적을 남기고, 4xx(클라이언트 원인)는 소음을 만들지 않는다.
 * 응답만 나가고 로그가 없으면 운영 조사가 불가능하다는 감사 결과의 보강.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private ListAppender<ILoggingEvent> logCaptor;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        // 실제 logback 로거에 수집용 appender를 붙여 "로그가 남았는가" 자체를 단언한다
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logCaptor = new ListAppender<>();
        logCaptor.start();
        logger.addAppender(logCaptor);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logCaptor);
    }

    @Test
    @DisplayName("5xx CustomException은 에러코드 이름과 함께 로그를 남긴다")
    void serverSideCustomException_isLogged() {
        ResponseEntity<?> response = handler.customExceptionHandler(
                new CustomException(ErrorCode.OAUTH_LOGIN_FAILED)); // 502

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(logCaptor.list).hasSize(1);
        assertThat(logCaptor.list.get(0).getFormattedMessage()).contains("OAUTH_LOGIN_FAILED");
    }

    @Test
    @DisplayName("4xx CustomException은 로그 소음을 만들지 않는다")
    void clientSideCustomException_isSilent() {
        ResponseEntity<?> response = handler.customExceptionHandler(
                new CustomException(ErrorCode.SESSION_NOT_FOUND)); // 404

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(logCaptor.list).isEmpty();
    }
}
