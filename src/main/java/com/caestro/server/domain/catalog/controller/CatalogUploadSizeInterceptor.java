package com.caestro.server.domain.catalog.controller;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 스튜디오 그림 올리기의 본문 크기를 읽기 전에 거른다 — 함께 채우기의 {@code FillUploadSizeInterceptor} 와 같은 이유로,
 * {@code @RequestBody byte[]} 가 본문 전체를 힙에 올리기 전에 Content-Length 만으로 끊는다.
 */
public class CatalogUploadSizeInterceptor implements HandlerInterceptor {

    private final long maxImageBytes;

    public CatalogUploadSizeInterceptor(long maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        long declared = request.getContentLengthLong();
        if (declared < 0) {
            throw new CustomException(ErrorCode.LENGTH_REQUIRED);
        }
        if (declared > maxImageBytes) {
            throw new CustomException(ErrorCode.CATALOG_IMAGE_TOO_LARGE);
        }
        return true;
    }
}
