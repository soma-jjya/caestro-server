package com.caestro.server.domain.fill.controller;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 함께 채우기 업로드(칸 사진·칸 클립·프레임 요소·GIF)의 본문 크기를 읽기 전에 거른다 (#149).
 * {@code @RequestBody byte[]}는 본문 전체를 힙에 올린 뒤에야 서비스가 크기를 보므로, 그 전에 Content-Length만으로 끊는다.
 * 길이를 밝히지 않은(chunked) 업로드는 얼마나 올지 모르므로 받지 않는다 — 앱(OkHttp)과 브라우저 fetch는 바이트 본문에 항상 길이를 붙인다.
 */
public class FillUploadSizeInterceptor implements HandlerInterceptor {

    private final long maxImageBytes;
    private final long maxGifBytes;
    private final long maxClipBytes;

    public FillUploadSizeInterceptor(long maxImageBytes, long maxGifBytes, long maxClipBytes) {
        this.maxImageBytes = maxImageBytes;
        this.maxGifBytes = maxGifBytes;
        this.maxClipBytes = maxClipBytes;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 같은 경로의 DELETE(칸 비우기)는 본문이 없다 — 업로드(PUT)만 본다
        if (!"PUT".equals(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        // 칸 클립도 GIF다 — 크기 초과는 GIF의 오류로 알린다
        boolean clip = uri.endsWith("/clip");
        boolean gif = clip || uri.endsWith("/gif");
        long limit = clip ? maxClipBytes : gif ? maxGifBytes : maxImageBytes;
        long declared = request.getContentLengthLong();
        if (declared < 0) {
            throw new CustomException(ErrorCode.LENGTH_REQUIRED);
        }
        if (declared > limit) {
            throw new CustomException(gif ? ErrorCode.FILL_GIF_TOO_LARGE : ErrorCode.FILL_IMAGE_TOO_LARGE);
        }
        return true;
    }
}
