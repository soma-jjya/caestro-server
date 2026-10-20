package com.caestro.server.global.config;

import com.caestro.server.domain.fill.controller.FillUploadSizeInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** MVC 인터셉터 등록. 업로드 크기 선검사는 본문을 읽는 인자 해석 단계보다 앞(preHandle)에서 돌아야 하므로 필터가 아닌 인터셉터로 둔다. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    // FillService와 같은 키를 읽는다 — 선검사 상한과 실제 저장 상한이 어긋나지 않게
    @Value("${fill.max-image-bytes:3145728}")
    private long maxImageBytes;

    @Value("${fill.max-gif-bytes:16777216}")
    private long maxGifBytes;

    @Value("${fill.max-clip-bytes:6291456}")
    private long maxClipBytes;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new FillUploadSizeInterceptor(maxImageBytes, maxGifBytes, maxClipBytes))
                .addPathPatterns("/fills/*/slots/*", "/fills/*/slots/*/clip", "/fills/*/frame-assets/*", "/fills/*/gif");
    }
}
