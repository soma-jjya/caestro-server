package com.caestro.server.global.exception;

import java.util.stream.Collectors;

import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.exception.error.ErrorDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.HttpStatusCode;


@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler{

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

    // 일반 예외처리
    @ExceptionHandler
    protected ResponseEntity<?> customServerException(Exception e){
        log.error("INTERNAL_SERVER_ERROR", e);
        ErrorDto errorDto = new ErrorDto(ErrorCode.INTERNAL_SERVER_ERROR.getStatus(), ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
        return new ResponseEntity<>(errorDto, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // 메소드 인자 타당성 예외 처리 (Bean Validation 실패도 공통 ErrorDto 포맷으로 통일)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ErrorDto> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        ErrorDto errorDto = new ErrorDto(HttpStatus.BAD_REQUEST.value(), message);
        return new ResponseEntity<>(errorDto, HttpStatus.BAD_REQUEST);
    }
}