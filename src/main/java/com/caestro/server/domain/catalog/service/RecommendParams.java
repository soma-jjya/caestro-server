package com.caestro.server.domain.catalog.service;

import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 추천 설정 params 검증·정리 (contracts/recommend-config.md). 모든 키는 선택이고(없으면 앱 기본값) null 값은 빠진 것으로 본다.
 * 모르는 키는 400 — 스튜디오에서 오타 낸 값이 앱에서 조용히 무시되지 않게 한다. 정리한 결과는 정해진 순서의 맵이고,
 * 숫자는 정수로 떨어지면 정수(25), 아니면 실수(0.55)로 둔다 — 앱이 Int 로 읽든 Double 로 읽든 깨지지 않게.
 */
final class RecommendParams {

    /** 허용 범위. integer 면 정수만(1500.0 은 1500 으로 받는다). */
    record Range(double min, double max, boolean integer) {
    }

    static final String PLACE = "place";
    static final String SCORING = "scoring";
    static final String PLACE_TO_POOL = "placeToPool";
    static final List<String> TOP_KEYS = List.of(PLACE, SCORING, PLACE_TO_POOL);

    // PlaceTracker: 확신 문턱·반감기·전환 여유·오래된 읽기·읽기 간격
    static final Map<String, Range> PLACE_RANGES = ordered(
            "sure", new Range(0, 1, false),
            "halfLifeMs", new Range(100, 60_000, true),
            "switchMargin", new Range(0, 1, false),
            "staleMs", new Range(1_000, 600_000, true),
            "intervalMs", new Range(250, 10_000, true));

    // 스냅샷 점수: score = bgDist + bgPoseWeight·poseDist (+ posturePenalty·conf), 목록 listSize 장
    static final Map<String, Range> SCORING_RANGES = ordered(
            "bgPoseWeight", new Range(0, 1_000, false),
            "posturePenalty", new Range(0, 1_000, false),
            "listSize", new Range(1, 200, true));

    static final int POOL_MAX_KEYS = 20;
    static final int POOL_MAX_VALUES = 20;
    static final int LABEL_MAX = 20;

    private RecommendParams() {
    }

    /** 검증하고 정리한 params. 틀리면 400 RECOMMEND_INVALID 에 무엇이 틀렸는지 덧붙인다. */
    static Map<String, Object> normalize(Map<String, Object> params) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (params == null) {
            return out;
        }
        rejectUnknown("params", params, TOP_KEYS);
        Map<String, Object> place = numbers("params.place", params.get(PLACE), PLACE_RANGES);
        if (place != null) {
            out.put(PLACE, place);
        }
        Map<String, Object> scoring = numbers("params.scoring", params.get(SCORING), SCORING_RANGES);
        if (scoring != null) {
            out.put(SCORING, scoring);
        }
        Map<String, Object> pool = placeToPool(params.get(PLACE_TO_POOL));
        if (pool != null) {
            out.put(PLACE_TO_POOL, pool);
        }
        return out;
    }

    private static Map<String, Object> numbers(String path, Object value, Map<String, Range> ranges) {
        if (value == null) {
            return null;
        }
        Map<String, Object> map = object(path, value);
        rejectUnknown(path, map, List.copyOf(ranges.keySet()));
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Range> e : ranges.entrySet()) {
            Object v = map.get(e.getKey());
            if (v == null) {
                continue;
            }
            out.put(e.getKey(), number(path + "." + e.getKey(), v, e.getValue()));
        }
        return out;
    }

    private static Number number(String path, Object value, Range range) {
        String expected = path + " 는 " + plain(range.min()) + "~" + plain(range.max()) + " 사이 " + (range.integer() ? "정수" : "숫자")
                + "여야 합니다 (받은 값: " + abbreviate(value instanceof String str ? "\"" + str + "\"" : String.valueOf(value)) + ")";
        if (!(value instanceof Number n)) {
            throw invalid(expected);
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || d < range.min() || d > range.max()) {
            throw invalid(expected);
        }
        boolean integral = d == Math.rint(d);
        if (range.integer() && !integral) {
            throw invalid(expected);
        }
        return integral ? (Number) (long) d : (Number) d;
    }

    private static Map<String, Object> placeToPool(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> map = object("params.placeToPool", value);
        if (map.size() > POOL_MAX_KEYS) {
            throw invalid("params.placeToPool 은 장소 " + POOL_MAX_KEYS + "개까지입니다");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String place = label("params.placeToPool 의 장소 이름", e.getKey());
            String path = "params.placeToPool." + place;
            if (!(e.getValue() instanceof List<?> list) || list.isEmpty() || list.size() > POOL_MAX_VALUES) {
                throw invalid(path + " 는 장소 이름 1~" + POOL_MAX_VALUES + "개 목록이어야 합니다");
            }
            // 같은 이름은 한 번만(순서는 보낸 대로)
            Set<String> labels = new LinkedHashSet<>();
            for (Object item : list) {
                labels.add(label(path + " 의 값", item));
            }
            if (out.containsKey(place)) {
                throw invalid("params.placeToPool 에 같은 장소가 두 번 있습니다: " + place);
            }
            out.put(place, new ArrayList<>(labels));
        }
        return out;
    }

    private static String label(String what, Object value) {
        String s = value instanceof String str ? str.strip() : null;
        if (s == null || s.isEmpty() || s.length() > LABEL_MAX) {
            throw invalid(what + "은 1~" + LABEL_MAX + "자 문자열이어야 합니다" + (s == null ? "" : " (" + abbreviate(s) + ")"));
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(String path, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(path + " 는 객체여야 합니다");
        }
        return (Map<String, Object>) map;
    }

    private static void rejectUnknown(String path, Map<String, Object> map, List<String> allowed) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(path + " 에 모르는 키가 있습니다: " + abbreviate(key) + " (" + String.join("·", allowed) + " 만)");
            }
        }
    }

    private static CustomException invalid(String detail) {
        return new CustomException(ErrorCode.RECOMMEND_INVALID, detail);
    }

    private static String plain(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static Map<String, Range> ordered(Object... pairs) {
        Map<String, Range> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Range) pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
