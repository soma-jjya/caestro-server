"""조사 루프: 알람 → 플레이북 선조회 → 모델이 도구를 골라 부르며 조사 → submit_report 로 결론.

모델 호출은 Anthropic SDK(Messages API) 직접 사용. 도구 호출 상한·토큰 상한을 두고,
결론은 반드시 submit_report 도구(strict 스키마)로 받아 형식을 고정한다. 근거 없는 주장은 코드에서 거른다.
"""

import json
import os
import re
import time

from playbooks import playbook_for
from redact import redact_deep
from tools import TOOL_SPECS, truncate_result

DEFAULT_MODEL = "claude-opus-5-5"
MAX_STEPS = 6               # 모델이 도구를 부르는 왕복 상한 (선조회 제외)
MAX_INPUT_TOKENS = 150_000  # 누적 입력 토큰 상한. 넘으면 결론을 강제한다
MAX_TOKENS_PER_CALL = 4000

CATEGORIES = ["external_scanner", "app_bug", "deploy_regression", "infra_failure", "capacity", "false_positive", "unknown"]
IMPACTS = ["none", "partial", "full", "unknown"]

REPORT_TOOL = {
    "name": "submit_report",
    "description": "조사를 마치고 결론을 제출한다. 모든 결론은 도구 결과의 수치를 인용해야 한다. 반드시 마지막에 한 번 호출한다.",
    "input_schema": {
        "type": "object",
        "properties": {
            "category": {"type": "string", "enum": CATEGORIES,
                         "description": "external_scanner=외부 스캐너·봇, app_bug=우리 코드 결함, deploy_regression=직전 배포가 원인, "
                                        "infra_failure=AWS 자원 장애, capacity=용량·한도, false_positive=알람 설계상 오탐, unknown=판단 불가"},
            "user_impact": {"type": "string", "enum": IMPACTS,
                            "description": "none=실사용 요청 영향 없음, partial=일부 요청·기능 실패, full=전면 장애, unknown=판단 불가"},
            "headline": {"type": "string", "description": "한 줄 결론, 80자 이내. 예: '외부 스캐너가 없는 경로 24건 호출, 사용자 영향 없음'"},
            "what_happened": {"type": "string", "description": "무슨 일이 있었는지 2~4문장"},
            "evidence": {"type": "array", "items": {"type": "string"},
                         "description": "근거. 각 항목은 도구 결과의 수치를 포함해야 한다(예: '500 응답 26건 중 24건이 POST /auth/callback')"},
            "probable_causes": {"type": "array", "items": {"type": "string"}, "description": "유력 원인 후보, 가능성 높은 순"},
            "next_checks": {"type": "array", "items": {"type": "string"}, "description": "사람이 바로 확인하거나 할 일"},
            "similar_past": {"type": "string", "description": "비슷한 과거 사례가 있으면 한 줄, 없으면 빈 문자열"},
        },
        "required": ["category", "user_impact", "headline", "what_happened", "evidence", "probable_causes", "next_checks", "similar_past"],
        "additionalProperties": False,
    },
    "strict": True,
}

SYSTEM_PROMPT = """당신은 PeakPic 서버의 운영 알람을 조사하는 SRE 보조다. 알람이 울린 직후 사람이 콘솔을 열기 전에,
도구로 사실을 모아 "무슨 일인지·사용자 영향·유력 원인·다음에 확인할 것"을 짧게 보고한다.

## 환경
- Spring Boot(Java 21) 앱, ALB 뒤 ASG(t3.micro 2대) 롤링 배포. Redis(ElastiCache)=시그널링 세션 상태, RDS(MySQL).
- 배포 = ASG instance refresh. 새 인스턴스가 떠서 ALB 헬시가 될 때까지 몇 분간 UnHealthyHostCount 가 1이 되는 것은 정상.
- t3 크레딧은 새 인스턴스가 0에서 시작한다. 배포 직후 잔고가 낮은 것은 소진이 아니다.
- 과거: 2026-09 외부 스캐너가 존재하지 않는 경로(/auth/callback, /.env 등)를 때려 500 이 났고 #137 에서 404 로 고쳤다.
  2026-09-01 #111 릴리스는 새 인스턴스가 기동에 실패했지만(빈 생성자 문제) 구버전 1대가 헬시를 유지해 사용자 영향은 없었다.
- 정상 사용자 경로: /auth/**, /sessions/**, /signaling/** (WebSocket), /fill/**, /shots/**, /catalog/**, /b2b/**, /inquiries.

## 규칙
1. 주장마다 도구 결과의 수치를 인용한다. 수치 없는 추측은 쓰지 않는다. 모르면 unknown 이라고 쓴다.
2. 사용자 영향은 "정상 사용자 경로에서 실패가 있었나"로 판단한다. 없는 경로만 실패했으면 none.
3. 도구는 필요한 것만 부른다. 선조회 결과로 충분하면 바로 결론을 낸다. 왕복 상한을 넘기면 결론이 강제된다.
4. 결론은 반드시 submit_report 도구로 제출한다. 한국어, 짧은 문장. 존댓말 없이 보고체.
5. 로그에 보이는 토큰·이메일 등은 이미 가려져 있다. 그대로 두고 언급하지 않는다.
"""


def run_tool(tools, name, args):
    """도구 하나를 실행하고 결과를 마스킹한다. 예외는 결과 dict 로 바꿔 조사가 끊기지 않게 한다."""
    fn = getattr(tools, name, None)
    if fn is None:
        return {"error": f"알 수 없는 도구 {name}"}
    try:
        return redact_deep(fn(**(args or {})))
    except Exception as e:  # noqa: BLE001 — 도구 실패도 모델에 알려 다른 도구로 우회하게 한다
        return {"error": f"{type(e).__name__}: {str(e)[:200]}"}


def build_user_message(alarm, playbook, prefetched):
    trigger = alarm.get("Trigger") or {}
    lines = [
        "## 알람",
        f"- 이름: {alarm.get('AlarmName')}",
        f"- 상태 변경 시각(UTC): {alarm.get('StateChangeTime')}",
        f"- 판정 근거: {alarm.get('NewStateReason')}",
        f"- 설명: {alarm.get('AlarmDescription') or ''}",
    ]
    if trigger.get("MetricName"):
        lines.append(f"- 지표: {trigger.get('Namespace')} {trigger.get('MetricName')} {trigger.get('Statistic')} "
                     f"{trigger.get('ComparisonOperator')} {trigger.get('Threshold')} "
                     f"({trigger.get('EvaluationPeriods')}회 × {trigger.get('Period')}초)")
    elif alarm.get("AlarmRule"):
        lines.append(f"- 복합 알람 규칙: {alarm.get('AlarmRule')}")
    lines += ["", "## 이 알람에서 먼저 볼 것", playbook["hint"], "", "## 선조회 결과"]
    for item in prefetched:
        lines.append(f"### {item['tool']} {json.dumps(item['args'], ensure_ascii=False)}")
        lines.append(truncate_result(item["result"]))
    depth = "선조회만으로 결론을 내도 된다. 추가 도구는 꼭 필요할 때만." if playbook["depth"] == "summary" \
        else "필요하면 도구를 더 불러 확인한 뒤 결론을 낸다."
    lines += ["", depth, "조사를 마치면 submit_report 를 호출한다."]
    return "\n".join(lines)


def investigate(alarm, tools, client, model=DEFAULT_MODEL, max_steps=MAX_STEPS, effort=None, fallbacks=True):
    """알람 하나를 조사한다. (report|None, trace, prefetched) 를 돌려준다."""
    started = time.time()
    playbook = playbook_for(alarm.get("AlarmName"))
    prefetched = [{"tool": name, "args": args, "result": run_tool(tools, name, args)} for name, args in playbook["prefetch"]]
    trace = {
        "model": model,
        "tool_calls": [p["tool"] for p in prefetched],
        "steps": 0,
        "input_tokens": 0,
        "output_tokens": 0,
        "stop": None,
        "elapsed_s": 0,
    }
    messages = [{"role": "user", "content": build_user_message(alarm, playbook, prefetched)}]
    request = {
        "model": model,
        "max_tokens": MAX_TOKENS_PER_CALL,
        "system": SYSTEM_PROMPT,
        "tools": TOOL_SPECS + [REPORT_TOOL],
        "output_config": {"effort": effort or os.environ.get("EFFORT", "medium")},
    }
    if fallbacks:
        # 안전 분류기가 요청을 거절하면 같은 호출 안에서 다른 모델로 이어 받는다(서버측 폴백)
        request["extra_headers"] = {"anthropic-beta": "server-side-fallback-2026-07-01"}
        request["extra_body"] = {"fallbacks": "default"}

    report = None
    nudged = False
    for step in range(max_steps + 2):
        response = client.messages.create(messages=messages, **request)
        usage = getattr(response, "usage", None)
        trace["input_tokens"] += getattr(usage, "input_tokens", 0) or 0
        trace["output_tokens"] += getattr(usage, "output_tokens", 0) or 0
        trace["steps"] = step + 1
        trace["stop"] = response.stop_reason

        if response.stop_reason == "refusal":
            break
        tool_uses = [b for b in response.content if getattr(b, "type", "") == "tool_use"]
        messages.append({"role": "assistant", "content": response.content})
        if not tool_uses:
            # 글만 쓰고 끝냈다 → 한 번만 보고서 제출을 요구한다
            if nudged:
                break
            nudged = True
            messages.append({"role": "user", "content": "글 대신 submit_report 도구로 결론을 제출하라."})
            continue

        results = []
        for use in tool_uses:
            if use.name == "submit_report":
                report = _clean_report(use.input)
                results.append({"type": "tool_result", "tool_use_id": use.id, "content": "접수됨"})
                continue
            trace["tool_calls"].append(use.name)
            payload = run_tool(tools, use.name, use.input)
            results.append({
                "type": "tool_result",
                "tool_use_id": use.id,
                "content": truncate_result(payload),
                "is_error": "error" in payload if isinstance(payload, dict) else False,
            })
        over_budget = trace["input_tokens"] > MAX_INPUT_TOKENS or step >= max_steps - 1
        if report is None and over_budget and not nudged:
            nudged = True
            results.append({"type": "text", "text": "도구 호출 상한에 닿았다. 지금까지의 결과로 submit_report 를 호출해 결론을 내라."})
        messages.append({"role": "user", "content": results})
        if report is not None:
            break

    trace["elapsed_s"] = round(time.time() - started, 1)
    return report, trace, prefetched


def _clean_report(raw):
    """스키마는 strict 로 보장되지만, '수치 없는 근거'는 여기서 거른다 — 규칙 1 을 코드로 강제."""
    report = dict(raw)
    evidence = [e for e in report.get("evidence", []) if isinstance(e, str) and re.search(r"\d", e)]
    dropped = len(report.get("evidence", [])) - len(evidence)
    report["evidence"] = evidence
    report["evidence_dropped"] = dropped
    if report.get("category") not in CATEGORIES:
        report["category"] = "unknown"
    if report.get("user_impact") not in IMPACTS:
        report["user_impact"] = "unknown"
    if not evidence:
        report["headline"] = "(근거 부족) " + report.get("headline", "")
        report["category"] = "unknown"
    return report
