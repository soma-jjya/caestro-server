package com.caestro.server.domain.catalog.controller;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 추천 설정 파일 올리기(POST /catalog/studio/recommend/files?kind=…)의 본문 크기를 읽기 전에 거른다 — {@link CatalogUploadSizeInterceptor} 와
 * 같은 이유인데 경로 하나에 kind 마다 상한이 달라서(헤드 JSON 5MB, 모델 32MB) 따로 둔다. kind 가 틀리면 작은 쪽 상한으로 보고
 * 나머지는 서비스가 400 으로 답한다.
 */
public class RecommendFileSizeInterceptor implements HandlerInterceptor {

    private final long maxHeadBytes;
    private final long maxModelBytes;

    public RecommendFileSizeInterceptor(long maxHeadBytes, long maxModelBytes) {
        this.maxHeadBytes = maxHeadBytes;
        this.maxModelBytes = maxModelBytes;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        long limit = "model".equals(request.getParameter("kind")) ? maxModelBytes : maxHeadBytes;
        long declared = request.getContentLengthLong();
        if (declared < 0) {
            throw new CustomException(ErrorCode.LENGTH_REQUIRED);
        }
        if (declared > limit) {
            throw new CustomException(ErrorCode.RECOMMEND_FILE_TOO_LARGE);
        }
        return true;
    }
}
