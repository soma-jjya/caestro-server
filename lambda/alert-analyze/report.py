"""조사 결과(report dict) → Discord 임베드. AWS·LLM 무관, 로컬 테스트 대상.

모양은 alert-notify 와 맞춘다(#254): 제목 '🔎 분석 · 사람 말 알람 제목 · 서비스', 본문 첫 줄은 굵은 결론 한 줄,
칸 이름은 한국어, 시스템 이름과 비용 수치는 footer.
"""

from alarm_catalog import alarm_headline

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

# 폴백 임베드의 칸 이름. 도구 이름 대신 사람이 아는 말로
TOOL_LABEL = {
    "query_logs": "앱 로그",
    "query_metrics": "지표",
    "query_access_logs": "접근 기록 (ALB)",
    "recent_deploys": "최근 배포",
}

EVIDENCE_LIMIT = 5
LIST_LIMIT = 3


def build_report_embed(alarm, report, trace):
    icon, color, impact = IMPACT_LABEL.get(report.get("user_impact"), IMPACT_LABEL["unknown"])
    category = CATEGORY_LABEL.get(report.get("category"), report.get("category", ""))
    fields = [{"name": "무슨 일이 있었나", "value": _clip(report.get("what_happened") or "-", 1024)}]
    if report.get("evidence"):
        fields.append({"name": "근거", "value": _bullets(report["evidence"], EVIDENCE_LIMIT)})
    if report.get("probable_causes"):
        fields.append({"name": "왜 그런 것 같나", "value": _bullets(report["probable_causes"], LIST_LIMIT)})
    if report.get("next_checks"):
        fields.append({"name": "지금 할 일", "value": _bullets(report["next_checks"], LIST_LIMIT)})
    if report.get("similar_past"):
        fields.append({"name": "비슷한 과거", "value": _clip(report["similar_past"], 512)})
    headline = _clip(report.get("headline") or "결론 없음", 200)
    return {
        "title": _clip(f"🔎 분석 · {alarm_headline(alarm.get('AlarmName', ''))}", 256),
        "description": f"**{headline}**\n{icon} {impact} · {category}",
        "color": color,
        "fields": fields,
        "footer": {"text": _footer(alarm, trace)},
    }


def add_issue_field(embed, outcome, mode):
    """이슈 처리 결과(#233)를 임베드 마지막 칸에 한 줄로. outcome 은 github_issues.sync 결과 또는 {"action": "gate"|"failed", "note"}."""
    action = outcome.get("action")
    number = outcome.get("number")
    url = outcome.get("url")
    link = f"[#{number}]({url})" if number and url else (f"#{number}" if number else "")
    if action == "created":
        value = f"{link} 새로 만듦"
    elif action == "commented":
        value = f"{link} {outcome.get('nth')}회째 코멘트"
    elif action == "skipped":
        value = f"{link} {outcome.get('note')} → 건너뜀"
    elif action == "shadow":
        value = f"만들 것 · {outcome.get('title', '')} · {outcome.get('note')}"
    elif action == "gate":
        value = f"만들지 않음 · {outcome.get('note')}"
    else:
        value = f"실패 · {outcome.get('note')}"
    name = "📝 이슈 (섀도)" if mode == "shadow" else "📝 이슈"
    embed.setdefault("fields", []).append({"name": name, "value": _clip(value, 1024)})
    return embed


def build_fallback_embed(alarm, prefetched, trace, reason):
    """모델이 결론을 못 냈을 때: 선조회한 원자료 요약이라도 올린다. 분석 실패가 침묵이 되면 안 된다."""
    fields = []
    for item in prefetched[:4]:
        label = TOOL_LABEL.get(item["tool"], item["tool"])
        fields.append({"name": label, "value": _clip(_raw_summary(item["result"]), 1024)})
    return {
        "title": _clip(f"⚪ 분석 못 함 · {alarm_headline(alarm.get('AlarmName', ''))}", 256),
        "description": _clip(f"모델이 결론을 못 냈어요 ({reason}). 자동으로 모은 자료만 올려요.", 2000),
        "color": COLOR_GRAY,
        "fields": fields,
        "footer": {"text": _footer(alarm, trace)},
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


def _bullets(items, limit):
    text = "\n".join(f"• {str(i)}" for i in items[:limit])
    if len(items) > limit:
        text += f"\n… 외 {len(items) - limit}개"
    return _clip(text, 1024) or "-"


def _clip(text, limit):
    text = str(text or "")
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _footer(alarm, trace):
    """시스템 알람 이름과 비용 수치. README 가 '실전 수치는 footer 에 찍힌다'고 약속한 자리다."""
    calls = len(trace.get("tool_calls", []))
    return (f"{alarm.get('AlarmName', '')} · 도구 {calls}회 · {trace.get('elapsed_s', 0)}초"
            f" · 토큰 {trace.get('input_tokens', 0):,}/{trace.get('output_tokens', 0):,}")
