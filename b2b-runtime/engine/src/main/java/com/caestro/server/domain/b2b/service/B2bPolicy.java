package com.caestro.server.domain.b2b.service;

import com.caestro.server.domain.catalog.dto.FrameWindowDto;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** B2B 입력 규칙. 카탈로그와 같은 창 좌표계이며 초안에는 미완성 목록을 허용한다. */
final class B2bPolicy {
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{3,40}$");
    private static final Set<String> RESERVED = Set.of("b2b", "fill", "admin", "auth", "api", "login", "assets",
            "static", "frames", "catalog", "d", "www", "app", "health", "studio");

    private B2bPolicy() {}

    static String id(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }

    static String slugProblem(String slug) {
        // 한 글자 예약어 d도 reserved로 분류한다.
        if (slug != null && RESERVED.contains(slug)) return "reserved";
        return slug == null || !SLUG.matcher(slug).matches() ? "format" : null;
    }

    static String text(String value, int max, boolean required, ErrorCode error) {
        String normalized = value == null ? "" : value.strip();
        if ((required && normalized.isEmpty()) || normalized.length() > max) throw new CustomException(error);
        return normalized;
    }

    /** 창이 있으면 개수·유한수·경계·각도를 검사한다. null은 초안의 빈 목록이다. */
    static List<FrameWindowDto> windows(List<FrameWindowDto> windows) {
        if (windows == null) return List.of();
        if (windows.size() > 4) throw new CustomException(ErrorCode.B2B_INVALID_FRAME);
        for (FrameWindowDto w : windows) {
            if (w == null || !Double.isFinite(w.x()) || !Double.isFinite(w.y()) || !Double.isFinite(w.w())
                    || !Double.isFinite(w.h()) || !Double.isFinite(w.rot())
                    || w.x() < 0 || w.y() < 0 || w.w() <= 0 || w.h() <= 0
                    || w.x() + w.w() > 1.001 || w.y() + w.h() > 1.001 || Math.abs(w.rot()) > 180) {
                throw new CustomException(ErrorCode.B2B_INVALID_FRAME);
            }
        }
        return List.copyOf(windows);
    }
}
