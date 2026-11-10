package com.caestro.server.domain.deveval.service;

import com.caestro.server.domain.deveval.dto.response.DevEvaluationRowResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 관리자 CSV 한 줄 (contracts/dev-evaluations.md). 열 이름은 JSON 필드 이름과 같고 photoScore 는 네 열로 편다.
 * RFC 4180 따옴표 규칙에, 글자 칸이 = + - @ 로 시작하면 앞에 ' 를 붙여 엑셀이 수식으로 실행하지 않게 한다.
 */
final class DevEvaluationCsv {

    static final List<String> HEADER = List.of(
            "id", "userId", "userNickname", "deviceKey", "rating", "ratingMax", "guide", "guideVersion", "guideShot", "guideStyle",
            "shutterScore", "photoScoreTotal", "photoScorePose", "photoScorePlace", "photoScoreShape", "mediaType",
            "takenAt", "ratedAt", "width", "height", "appVersion", "device", "note", "extra",
            "hasPhoto", "photoBytes", "createdAt", "updatedAt");

    private DevEvaluationCsv() {
    }

    static String line(DevEvaluationRowResponse r, Function<Map<String, Object>, String> json) {
        List<String> cells = new ArrayList<>(HEADER.size());
        cells.add(text(r.id()));
        cells.add(value(r.userId()));
        cells.add(text(r.userNickname()));
        cells.add(text(r.deviceKey()));
        cells.add(value(r.rating()));
        cells.add(value(r.ratingMax()));
        cells.add(value(r.guide()));
        cells.add(text(r.guideVersion()));
        cells.add(text(r.guideShot()));
        cells.add(text(r.guideStyle()));
        cells.add(number(r.shutterScore()));
        cells.add(number(r.photoScore() == null ? null : r.photoScore().total()));
        cells.add(number(r.photoScore() == null ? null : r.photoScore().pose()));
        cells.add(number(r.photoScore() == null ? null : r.photoScore().place()));
        cells.add(number(r.photoScore() == null ? null : r.photoScore().shape()));
        cells.add(text(r.mediaType()));
        cells.add(value(r.takenAt()));
        cells.add(value(r.ratedAt()));
        cells.add(value(r.width()));
        cells.add(value(r.height()));
        cells.add(text(r.appVersion()));
        cells.add(text(r.device()));
        cells.add(text(r.note()));
        cells.add(text(json.apply(r.extra())));
        cells.add(value(r.hasPhoto()));
        cells.add(value(r.photoBytes()));
        cells.add(value(r.createdAt()));
        cells.add(value(r.updatedAt()));
        return String.join(",", cells);
    }

    /** 숫자·불리언·시각: 따옴표가 필요 없다. null 은 빈 칸. */
    private static String value(Object v) {
        return v == null ? "" : v.toString();
    }

    /** 81.0 → 81, 0.97 → 0.97. */
    private static String number(Double v) {
        if (v == null) {
            return "";
        }
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf(v.longValue()) : v.toString();
    }

    static String text(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
