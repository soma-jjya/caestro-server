package com.caestro.server.domain.catalog.service;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 장소 헤드 JSON 의 모양 검증 (contracts/recommend-config.md). 앱의 {@code assets/scene/place_head.json} 과 같은 꼴이어야 한다:
 * classes(풀 장소 이름들, 보통 끝이 "기타") 2~20개, places365_bucket 은 Places365 365칸 각각이 몇 번째 class 인지,
 * weights 는 class 마다 (feature_dim + class 수) 개의 숫자, bias 는 class 수만큼. 다른 키(버전 표시 등)는 그대로 둔다.
 * 틀리면 400 RECOMMEND_HEAD_INVALID 에 무엇이 틀렸는지 덧붙인다 — 앱은 못 읽는 헤드를 받으면 번들로 돌아가지만 스튜디오에서 먼저 막는다.
 */
final class RecommendHeadValidator {

    static final int PLACES365 = 365;
    static final int CLASSES_MIN = 2;
    static final int CLASSES_MAX = 20;

    private RecommendHeadValidator() {
    }

    static void validate(byte[] body, ObjectMapper objectMapper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw invalid("JSON 으로 읽을 수 없습니다");
        }
        if (root == null || !root.isObject()) {
            throw invalid("최상위가 JSON 객체여야 합니다");
        }

        JsonNode classes = root.get("classes");
        if (classes == null || !classes.isArray() || classes.size() < CLASSES_MIN || classes.size() > CLASSES_MAX) {
            throw invalid("classes 는 문자열 " + CLASSES_MIN + "~" + CLASSES_MAX + "개 배열이어야 합니다");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < classes.size(); i++) {
            JsonNode c = classes.get(i);
            if (!c.isTextual() || c.asText().isBlank()) {
                throw invalid("classes[" + i + "] 가 빈 문자열이거나 문자열이 아닙니다");
            }
            if (!seen.add(c.asText().strip())) {
                throw invalid("classes 에 같은 이름이 두 번 있습니다: " + c.asText().strip());
            }
        }
        int n = classes.size();

        JsonNode bucket = root.get("places365_bucket");
        if (bucket == null || !bucket.isArray() || bucket.size() != PLACES365) {
            throw invalid("places365_bucket 은 정수 " + PLACES365 + "개 배열이어야 합니다"
                    + (bucket != null && bucket.isArray() ? " (지금 " + bucket.size() + "개)" : ""));
        }
        for (int i = 0; i < PLACES365; i++) {
            Long v = integral(bucket.get(i));
            if (v == null || v < 0 || v >= n) {
                throw invalid("places365_bucket[" + i + "] 는 0~" + (n - 1) + " 사이 정수(classes 의 번호)여야 합니다");
            }
        }

        Long featureDim = integral(root.get("feature_dim"));
        if (featureDim == null || featureDim <= 0 || featureDim > 1_000_000) {
            throw invalid("feature_dim 은 1 이상의 정수여야 합니다");
        }
        long rowLength = featureDim + n;

        JsonNode weights = root.get("weights");
        if (weights == null || !weights.isArray() || weights.size() != n) {
            throw invalid("weights 는 classes 수(" + n + ")만큼의 행이어야 합니다"
                    + (weights != null && weights.isArray() ? " (지금 " + weights.size() + "행)" : ""));
        }
        for (int r = 0; r < n; r++) {
            JsonNode row = weights.get(r);
            if (row == null || !row.isArray() || row.size() != rowLength) {
                throw invalid("weights[" + r + "] 는 숫자 " + rowLength + "개(feature_dim " + featureDim + " + classes " + n + ")여야 합니다"
                        + (row != null && row.isArray() ? " (지금 " + row.size() + "개)" : ""));
            }
            for (int c = 0; c < rowLength; c++) {
                if (!row.get(c).isNumber()) {
                    throw invalid("weights[" + r + "][" + c + "] 가 숫자가 아닙니다");
                }
            }
        }

        JsonNode bias = root.get("bias");
        if (bias == null || !bias.isArray() || bias.size() != n) {
            throw invalid("bias 는 숫자 " + n + "개(classes 수) 배열이어야 합니다"
                    + (bias != null && bias.isArray() ? " (지금 " + bias.size() + "개)" : ""));
        }
        for (int i = 0; i < n; i++) {
            if (!bias.get(i).isNumber()) {
                throw invalid("bias[" + i + "] 가 숫자가 아닙니다");
            }
        }
    }

    /** 정수로 떨어지는 숫자(3, 3.0)면 그 값, 아니면 null. */
    private static Long integral(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? node.asLong() : null;
        }
        double d = node.asDouble();
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? (long) d : null;
    }

    private static CustomException invalid(String detail) {
        return new CustomException(ErrorCode.RECOMMEND_HEAD_INVALID, detail);
    }
}
