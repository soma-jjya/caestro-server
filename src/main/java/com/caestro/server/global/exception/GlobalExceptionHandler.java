package com.caestro.server.global.exception;

import java.util.stream.Collectors;

import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.exception.error.ErrorDto;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;


// 스프링 MVC 표준 예외(없는 경로·미지원 메서드 등)는 부모가 본래 상태 코드로 분류한다 (#137).
// 범용 핸들러가 이들까지 500으로 바꿔 5xx 알람이 오탐하던 문제의 수정.
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // 커스텀 예외처리
    @ExceptionHandler(CustomException.class)
    protected ResponseEntity<?> customExceptionHandler(CustomException e) {
        ErrorCode errorCode = e.getErrorCode();
        // 5xx 계열은 서버 측 이상 — 응답만 나가고 흔적이 없으면 조사가 불가능하므로 로그를 남긴다 (#131).
        // 4xx는 클라이언트 원인의 정상 흐름이라 로그 소음을 만들지 않는다.
        if (errorCode.getStatus() >= 500) {
            log.warn("Server-side CustomException: {} ({})", errorCode.name(), errorCode.getStatus());
        }
        ErrorDto errorDto = new ErrorDto(errorCode.getStatus(), errorCode.getMessage());
        return new ResponseEntity<>(errorDto, HttpStatusCode.valueOf(errorCode.getStatus()));
    }

    // 정렬(sort) 파라미터에 존재하지 않는 필드가 지정된 경우 (예: sort=DESC) → 400 처리
    @ExceptionHandler(PropertyReferenceException.class)
    protected ResponseEntity<ErrorDto> handleInvalidSort(PropertyReferenceException e) {
        ErrorCode errorCode = ErrorCode.INVALID_SORT_PARAMETER;
        ErrorDto errorDto = new ErrorDto(errorCode.getStatus(), errorCode.getMessage());
        return new ResponseEntity<>(errorDto, HttpStatusCode.valueOf(errorCode.getStatus()));
    }

    // 일반 예외처리: 분류되지 않은 진짜 서버 오류. 조사할 수 있게 메서드와 경로를 함께 남긴다 (#137)
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<?> customServerException(Exception e, HttpServletRequest request) {
        log.error("INTERNAL_SERVER_ERROR {} {}", request.getMethod(), request.getRequestURI(), e);
        ErrorDto errorDto = new ErrorDto(ErrorCode.INTERNAL_SERVER_ERROR.getStatus(), ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
        return new ResponseEntity<>(errorDto, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // 메소드 인자 타당성 예외 처리 (Bean Validation 실패도 공통 ErrorDto 포맷으로 통일)
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        ErrorDto errorDto = new ErrorDto(HttpStatus.BAD_REQUEST.value(), message);
        return handleExceptionInternal(e, errorDto, headers, HttpStatus.BAD_REQUEST, request);
    }

    // 표준 예외의 공통 출구: 부모가 정한 상태 코드는 유지하고 본문만 공통 ErrorDto로 맞춘다
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // 로깅 정책(#131)과 동일: 5xx만 흔적을 남기고 4xx는 조용히 응답한다
        if (status.is5xxServerError() && request instanceof ServletWebRequest servletRequest) {
            log.error("INTERNAL_SERVER_ERROR {} {}", servletRequest.getRequest().getMethod(),
                    servletRequest.getRequest().getRequestURI(), e);
        }
        Object responseBody = (body instanceof ErrorDto) ? body : new ErrorDto(status.value(), messageFor(status));
        return super.handleExceptionInternal(e, responseBody, headers, status, request);
    }

    private static String messageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND.getMessage();
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED.getMessage();
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE.getMessage();
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE.getMessage();
            default -> status.is5xxServerError()
                    ? ErrorCode.INTERNAL_SERVER_ERROR.getMessage()
                    : ErrorCode.BAD_REQUEST.getMessage();
        };
    }
}
