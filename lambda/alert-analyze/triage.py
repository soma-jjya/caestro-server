"""분석 결론 → GitHub 이슈 판단(#233): 문지기 · 지문 · 본문. 순수 함수만 있고 GitHub 호출은 github_issues.py.

이슈의 가치는 생성 자체가 아니라 '언제 만들고 언제 만들지 않는지'다. 세 조건을 모두 만족할 때만 만든다:
  1. 분류가 app_bug·deploy_regression            (스캐너·오탐·용량·원인 미상은 Discord 에서 끝)
  2. 수치가 붙은 근거가 MIN_EVIDENCE 건 이상       (_clean_report 가 수치 없는 근거를 버린 뒤 기준)
  3. 스택 트레이스에 우리 패키지(com.caestro)가 보이는 예외가 1건 이상
본문은 모델 보고서와 예외 클래스·치환한 경로로만 조립하고, 공개 레포에 못 들어갈 값은 scrub 으로 지운다.
"""

import hashlib
import re

from redact import redact

ISSUE_CATEGORIES = ("app_bug", "deploy_regression")
MIN_EVIDENCE = 2
OUR_PACKAGE = "com.caestro"
LABELS = ["auto-triage", "bug"]
FALSE_POSITIVE_LABEL = "auto-triage:false-positive"
FRAME_LIMIT = 3
TITLE_LIMIT = 120
BODY_LIMIT = 20000

CATEGORY_WORD = {"app_bug": "앱 결함", "deploy_regression": "배포 회귀", "external_scanner": "외부 스캐너·봇",
                 "infra_failure": "인프라 장애", "capacity": "용량·한도", "false_positive": "알람 오탐", "unknown": "원인 미상"}
IMPACT_WORD = {"none": "없음", "partial": "일부 요청", "full": "전면", "unknown": "판단 불가"}

_EXCEPTION_LINE = re.compile(r"^([A-Za-z_$][\w$.]*(?:Exception|Error|Throwable|Failure)[\w$]*)(?::|\s|$)")
_ID_SEGMENT = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{16,}", re.I)
_NUMBER_SEGMENT = re.compile(r"\d+")

# 공개 레포 본문에서 지우는 것. 토큰·이메일·전화번호는 redact() 가 맡는다.
_SCRUB_RULES = [
    (re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b"), "[IP]"),
    (re.compile(r"\b(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{1,4}\b", re.I), "[IP]"),
    (re.compile(r"\b\d{12}\b"), "[ACCOUNT]"),
    (re.compile(r"\b(?:Mozilla|curl|python-requests|Go-http-client|okhttp|zgrab|masscan|Nmap)/[^\s\"'),]*(?:\s*\([^)]*\))?"), "[UA]"),
    (re.compile(r"\barn:aws:[^\s\"']+"), "[ARN]"),
]


def decide(alarm, report, observations, when=None):
    """문지기 판정과 이슈 초안. {"ok", "reasons", "draft"}. draft 는 ok 일 때만 있다.

    observations: 선조회 + 모델이 추가로 부른 도구 결과 [{"tool", "args", "result"}].
    when: 알람 시각(aware datetime). 없으면 StateChangeTime 문자열을 그대로 쓴다.
    """
    reasons = []
    category = report.get("category")
    if category not in ISSUE_CATEGORIES:
        reasons.append(f"분류가 {CATEGORY_WORD.get(category, category)}")
    evidence = [e for e in report.get("evidence", []) if isinstance(e, str) and re.search(r"\d", e)]
    if len(evidence) < MIN_EVIDENCE:
        reasons.append(f"근거 {len(evidence)}건 ({MIN_EVIDENCE}건 필요)")
    exceptions = our_exceptions(observations)
    if not exceptions:
        reasons.append("우리 코드 예외 없음")
    if reasons:
        return {"ok": False, "reasons": reasons, "draft": None}
    exception = exceptions[0]
    path = normalize_path(top_path(observations))
    fp = fingerprint(alarm.get("AlarmName", ""), exception["exception"], path)
    return {"ok": True, "reasons": [], "draft": {
        "title": issue_title(report),
        "fingerprint": fp,
        "exception": exception,
        "path": path,
        "labels": list(LABELS),
        "alarm": alarm.get("AlarmName", ""),
        "when": _when_text(alarm, when),
        "report": {**report, "evidence": evidence},
    }}


def our_exceptions(observations):
    """query_logs 결과 행 중 stack_trace 에 우리 패키지가 보이는 것을 예외 클래스별로 묶는다. 많은 순.
    [{"exception": 최상위 예외 클래스, "count": 행 수, "frames": 우리 코드 줄 FRAME_LIMIT 개}]"""
    seen = set()
    groups = {}
    for item in observations or []:
        result = item.get("result") if isinstance(item, dict) else None
        if item.get("tool") != "query_logs" or not isinstance(result, dict):
            continue
        for row in result.get("rows") or []:
            stack = row.get("stack_trace") if isinstance(row, dict) else None
            if not isinstance(stack, str) or OUR_PACKAGE not in stack:
                continue
            key = (row.get("@timestamp"), row.get("message"), stack[:200])
            if key in seen:
                continue
            seen.add(key)
            exc = top_exception(stack)
            if not exc:
                continue
            group = groups.setdefault(exc, {"exception": exc, "count": 0, "frames": []})
            group["count"] += 1
            if not group["frames"]:
                group["frames"] = our_frames(stack)
    return sorted(groups.values(), key=lambda g: -g["count"])


def top_exception(stack):
    """스택 첫 줄의 예외 클래스. 'org.x.FooException: msg' → 'org.x.FooException'. 아니면 빈 문자열."""
    first = (stack or "").strip().split("\n", 1)[0].strip()
    m = _EXCEPTION_LINE.match(first)
    return m.group(1) if m else ""


def our_frames(stack, limit=FRAME_LIMIT):
    """우리 코드를 가리키는 줄 limit 개. 'at com.caestro…' 프레임이 먼저, 없으면 패키지 이름이 든 줄(원인 메시지 등)."""
    lines = [line.strip() for line in (stack or "").split("\n")]
    frames = [line for line in lines if line.startswith("at " + OUR_PACKAGE)]
    if not frames:
        frames = [line for line in lines if OUR_PACKAGE in line]
    return frames[:limit]


def top_path(observations):
    """접근 기록 집계에서 가장 많은 요청의 경로. 'POST /auth/callback' → '/auth/callback'. 없으면 빈 문자열."""
    for item in observations or []:
        result = item.get("result") if isinstance(item, dict) else None
        if item.get("tool") != "query_access_logs" or not isinstance(result, dict):
            continue
        top = result.get("top_requests") or []
        if top and isinstance(top[0], dict):
            request = str(top[0].get("request", ""))
            return request.split(" ", 1)[1] if " " in request else request
    return ""


def normalize_path(path):
    """경로의 식별자를 치환해 같은 엔드포인트가 같은 지문을 갖게 한다. UUID·긴 16진수 → {id}, 숫자 → {n}. 쿼리스트링 제거."""
    path = (path or "").split("?", 1)[0]
    path = _ID_SEGMENT.sub("{id}", path)
    return _NUMBER_SEGMENT.sub("{n}", path)


def fingerprint(alarm_name, exception, path):
    """최상위 예외 클래스 + 치환한 경로 + 알람 이름 → 8자리 해시. 이슈 본문의 <!-- fp:… --> 와 검색에 쓴다."""
    raw = f"{exception}|{path}|{alarm_name}"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:8]


def marker(fp):
    """본문·코멘트에 눈에 보이게 넣는 지문 표식. 검색(in:body)으로 찾아야 하므로 HTML 주석에 숨기지 않는다."""
    return f"fp:{fp}"


def issue_title(report):
    return _clip(f"[자동] {report.get('headline') or '알람 분석 결과'}", TITLE_LIMIT)


def issue_body(draft, secrets=()):
    """공개 레포용 본문. 모델 보고서 + 예외 클래스·우리 코드 프레임·치환한 경로만 넣고, scrub 으로 금지값을 지운다."""
    report = draft["report"]
    exc = draft["exception"]
    lines = [
        "> **자동 생성 · 사람 확인 필요.** 알람 분석 Lambda(#230 → #233)가 만든 이슈입니다. 사실관계를 확인한 뒤 제목·라벨을 고쳐 쓰세요.",
        f"> 오탐이면 `{FALSE_POSITIVE_LABEL}` 라벨을 달아 주세요. 같은 지문으로는 다시 만들지도, 코멘트를 달지도 않습니다.",
        "",
        "## 결론",
        f"**{report.get('headline', '')}**",
        "",
        f"분류 {CATEGORY_WORD.get(report.get('category'), report.get('category'))} · 사용자 영향 {IMPACT_WORD.get(report.get('user_impact'), report.get('user_impact'))}"
        f" · 알람 `{draft['alarm']}` · {draft['when']}",
        "",
        "## 무슨 일이 있었나",
        report.get("what_happened", "") or "-",
        "",
        "## 근거",
        *[f"- {e}" for e in report.get("evidence", [])],
        "",
        "## 예외",
        f"`{exc['exception']}` — 분석 창 안 에러 로그 {exc['count']}건",
    ]
    if draft.get("path"):
        lines.append(f"경로(치환): `{draft['path']}`")
    if exc.get("frames"):
        lines += ["", "```", *exc["frames"], "```"]
    if report.get("probable_causes"):
        lines += ["", "## 왜 그런 것 같나", *[f"- {c}" for c in report["probable_causes"]]]
    if report.get("next_checks"):
        lines += ["", "## 확인할 것", *[f"- [ ] {c}" for c in report["next_checks"]]]
    if report.get("similar_past"):
        lines += ["", "## 비슷한 과거", report["similar_past"]]
    lines += ["", "분석 전문은 Discord 알림 채널의 🔎 분석 글(같은 알람·시각)에 있습니다.", "",
              f"지문 `{marker(draft['fingerprint'])}`"]
    return _clip(scrub("\n".join(lines), secrets), BODY_LIMIT)


def comment_body(nth, draft, secrets=()):
    """재발 코멘트: 'N회째 · 시각 · 건수'. 지문 표식을 넣어 다음 회차를 셀 수 있게 한다."""
    report = draft["report"]
    first = report["evidence"][0] if report.get("evidence") else ""
    lines = [f"**{nth}회째** · {draft['when']} · {first}", "", f"결론: {report.get('headline', '')}",
             *[f"- {e}" for e in report.get("evidence", [])[1:3]], "", f"지문 `{marker(draft['fingerprint'])}`"]
    return _clip(scrub("\n".join(lines), secrets), BODY_LIMIT)


def scrub(text, secrets=()):
    """IP·계정 ID·User-Agent·ARN 과, 환경변수로 받은 인프라 이름(로그 그룹·버킷·ASG 등)을 지운 뒤 redact() 를 거친다."""
    text = str(text or "")
    for secret in sorted({s for s in secrets if s and len(s) >= 4}, key=len, reverse=True):
        text = text.replace(secret, "[REDACTED]")
    for pattern, repl in _SCRUB_RULES:
        text = pattern.sub(repl, text)
    return redact(text)


def _when_text(alarm, when):
    if when is not None:
        return when.strftime("%Y-%m-%d %H:%M UTC")
    return str(alarm.get("StateChangeTime", ""))


def _clip(text, limit):
    text = str(text or "")
    return text if len(text) <= limit else text[: limit - 1] + "…"
