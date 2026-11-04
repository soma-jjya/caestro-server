"""조사 결과(report dict) → Discord 임베드. AWS·LLM 무관, 로컬 테스트 대상."""

COLOR_RED = 15158332
COLOR_AMBER = 16098851
COLOR_GREEN = 3066993
COLOR_GRAY = 9807270

IMPACT_LABEL = {
    "none": ("🟢", COLOR_GREEN, "사용자 영향 없음"),
    "partial": ("🟠", COLOR_AMBER, "일부 요청 영향"),
    "full": ("🔴", COLOR_RED, "전면 장애 의심"),
    "unknown": ("⚪", COLOR_GRAY, "영향 판단 불가"),
}

CATEGORY_LABEL = {
    "external_scanner": "외부 스캐너·봇",
    "app_bug": "앱 결함",
    "deploy_regression": "배포 회귀",
    "infra_failure": "인프라 장애",
    "capacity": "용량·한도",
    "false_positive": "알람 오탐",
    "unknown": "원인 미상",
}


def build_report_embed(alarm, report, trace):
    icon, color, impact = IMPACT_LABEL.get(report.get("user_impact"), IMPACT_LABEL["unknown"])
    category = CATEGORY_LABEL.get(report.get("category"), report.get("category", ""))
    fields = [
        {"name": "분류", "value": f"{category} · {impact}", "inline": False},
        {"name": "무슨 일", "value": _clip(report.get("what_happened") or "-", 1024)},
    ]
    if report.get("evidence"):
        fields.append({"name": "근거", "value": _bullets(report["evidence"])})
    if report.get("probable_causes"):
        fields.append({"name": "유력 원인", "value": _bullets(report["probable_causes"])})
    if report.get("next_checks"):
        fields.append({"name": "확인할 것", "value": _bullets(report["next_checks"])})
    if report.get("similar_past"):
        fields.append({"name": "과거 사례", "value": _clip(report["similar_past"], 512)})
    return {
        "title": _clip(f"{icon} 분석 · {alarm.get('AlarmName', '')}", 256),
        "description": _clip(report.get("headline") or "", 2000),
        "color": color,
        "fields": fields,
        "footer": {"text": _footer(trace)},
    }


def build_fallback_embed(alarm, prefetched, trace, reason):
    """모델이 결론을 못 냈을 때: 선조회한 원자료 요약이라도 올린다. 분석 실패가 침묵이 되면 안 된다."""
    fields = []
    for item in prefetched[:4]:
        fields.append({"name": item["tool"], "value": _clip(_raw_summary(item["result"]), 1024)})
    return {
        "title": _clip(f"⚪ 분석 실패 · {alarm.get('AlarmName', '')}", 256),
        "description": _clip(f"모델 결론 없음 ({reason}). 아래는 자동 수집한 원자료 요약.", 2000),
        "color": COLOR_GRAY,
        "fields": fields,
        "footer": {"text": _footer(trace)},
    }


def _raw_summary(result):
    """도구 결과에서 사람이 먼저 볼 숫자만 뽑는다."""
    if not isinstance(result, dict):
        return str(result)[:300]
    if "error" in result:
        return f"오류: {result['error']}"
    if "by_status" in result:
        ips = ", ".join(f"{i['ip']}({i['n']})" for i in result.get("top_client_ips", [])[:3])
        return f"요청 {result.get('requests_in_window')}건, 조건 일치 {result.get('matched')}건, 상태 {result.get('by_status')}, IP 상위 {ips}"
    if "rows" in result:
        rows = result["rows"]
        head = "; ".join(_row_line(r) for r in rows[:5])
        return f"{result.get('kind')} {len(rows)}행 — {head}" if rows else f"{result.get('kind')}: 해당 로그 없음"
    if "summary" in result and "metric" in result:
        s = result["summary"]
        return f"{result['metric']} min {s.get('min')} / max {s.get('max')} / last {s.get('last')} ({s.get('count')}점)"
    if "refreshes" in result:
        r = result["refreshes"][:1]
        last = f"{r[0]['status']} 시작 {r[0]['start']} (알람 {r[0]['minutes_before_alarm']}분 전)" if r else "기록 없음"
        return f"최근 배포: {last}"
    return str(result)[:300]


def _row_line(row):
    parts = [row.get("level") or "", row.get("logger_name", "").split(".")[-1], (row.get("message") or "")[:80]]
    return " ".join(p for p in parts if p)


def _bullets(items, limit=1024):
    text = "\n".join(f"• {str(i)}" for i in items[:8])
    return _clip(text, limit) or "-"


def _clip(text, limit):
    text = str(text or "")
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _footer(trace):
    calls = len(trace.get("tool_calls", []))
    return (f"{trace.get('model', '')} · 도구 {calls}회 · 토큰 {trace.get('input_tokens', 0)}/{trace.get('output_tokens', 0)}"
            f" · {trace.get('elapsed_s', 0)}초")
