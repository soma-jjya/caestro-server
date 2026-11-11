package com.caestro.server.domain.deveval.controller;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 평가 기록(JSON)·사진 올리기의 본문 크기를 읽기 전에 거른다 — 함께 채우기 {@code FillUploadSizeInterceptor} 와 같은 이유로
 * Content-Length 만 보고 끊는다. 길이를 밝히지 않은(chunked) 업로드는 받지 않는다(OkHttp 는 바이트·JSON 본문에 길이를 붙인다).
 */
public class DevEvaluationUploadSizeInterceptor implements HandlerInterceptor {

    private final long maxRecordBytes;
    private final long maxPhotoBytes;

    public DevEvaluationUploadSizeInterceptor(long maxRecordBytes, long maxPhotoBytes) {
        this.maxRecordBytes = maxRecordBytes;
        this.maxPhotoBytes = maxPhotoBytes;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"PUT".equals(request.getMethod())) {
            return true;
        }
        // 사진과 가이드 이미지(/guide/photo·/guide/line)는 사진 상한, 나머지는 기록 상한
        String uri = request.getRequestURI();
        boolean photo = uri.endsWith("/photo") || uri.contains("/guide/");
        long declared = request.getContentLengthLong();
        if (declared < 0) {
            throw new CustomException(ErrorCode.LENGTH_REQUIRED);
        }
        if (declared > (photo ? maxPhotoBytes : maxRecordBytes)) {
            throw new CustomException(photo ? ErrorCode.DEV_EVAL_PHOTO_TOO_LARGE : ErrorCode.DEV_EVAL_TOO_LARGE);
        }
        return true;
    }
}
