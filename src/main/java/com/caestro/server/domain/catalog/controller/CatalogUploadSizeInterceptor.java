package com.caestro.server.domain.catalog.controller;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 스튜디오 그림·동영상 올리기의 본문 크기를 읽기 전에 거른다 — 함께 채우기의 {@code FillUploadSizeInterceptor} 와 같은 이유로,
 * {@code @RequestBody byte[]} 가 본문 전체를 힙에 올리기 전에 Content-Length 만으로 끊는다. 경로마다 상한과 오류가 다르다.
 */
public class CatalogUploadSizeInterceptor implements HandlerInterceptor {

    private final long maxBytes;
    private final ErrorCode tooLarge;

    public CatalogUploadSizeInterceptor(long maxImageBytes) {
        this(maxImageBytes, ErrorCode.CATALOG_IMAGE_TOO_LARGE);
    }

    public CatalogUploadSizeInterceptor(long maxBytes, ErrorCode tooLarge) {
        this.maxBytes = maxBytes;
        this.tooLarge = tooLarge;
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
        if (declared > maxBytes) {
            throw new CustomException(tooLarge);
        }
        return true;
    }
}
