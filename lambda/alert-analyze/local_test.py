"""로컬 검증: AWS·LLM 없이 마스킹, 액세스 로그 파서, 플레이북, 임베드, 조사 루프(가짜 모델)를 확인한다.

    python3 local_test.py
"""

import json
import sys
from datetime import datetime, timezone
from types import SimpleNamespace

from fixtures import FixtureTools
from handler import parse_alarm_time, should_analyze
from investigate import REPORT_TOOL, _clean_report, investigate
from playbooks import DEFAULT_PLAYBOOK, PLAYBOOKS, playbook_for
from redact import redact, redact_deep
from report import build_fallback_embed, build_report_embed
from tools import METRIC_SPECS, TOOL_SPECS, parse_alb_line, summarize_access_entries

CASE_DIR = __import__("pathlib").Path(__file__).parent / "eval" / "cases"


def test_redact():
    text = ("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijklmnop "
            "user a.b@example.com 010-1234-5678 key AKIAABCDEFGHIJKLMNOP sk-ant-api03-xxxxxxxxxxxx")
    out = redact(text)
    for leak in ("eyJ", "example.com", "1234-5678", "AKIA", "sk-ant-api03"):
        assert leak not in out, f"마스킹 누락: {leak} → {out}"
    nested = redact_deep({"a": ["x@y.co", {"b": "010 9999 8888"}]})
    assert nested == {"a": ["[EMAIL]", {"b": "[PHONE]"}]}, nested


def test_alb_parser():
    line = ('https 2026-09-29T03:16:02.123456Z app/peakpic-alb/abc 203.0.113.7:51234 10.0.1.20:8080 0.001 0.004 0.000 '
            '500 500 312 221 "POST https://api.example.com:443/auth/callback?x=1 HTTP/1.1" '
            '"Mozilla/5.0 (compatible; zgrab/0.x)" ECDHE-RSA-AES128-GCM-SHA256 TLSv1.2 '
            'arn:aws:elasticloadbalancing:ap-northeast-2:1:targetgroup/peakpic-tg/x "Root=1-abc" "api.example.com" '
            '"arn:aws:acm:..." 0 2026-09-29T03:16:02.118000Z "forward" "-" "-" "10.0.1.20:8080" "500" "-" "-"')
    entry = parse_alb_line(line)
    assert entry and entry["client_ip"] == "203.0.113.7" and entry["status"] == 500, entry
    assert entry["method"] == "POST" and entry["path"] == "/auth/callback", entry
    assert parse_alb_line("garbage") is None
    start = datetime(2026, 9, 29, 3, 10, tzinfo=timezone.utc)
    summary = summarize_access_entries([entry, {**entry, "status": 200, "path": "/sessions"}], status_min=500, window="w")
    assert summary["requests_in_window"] == 2 and summary["matched"] == 1, summary
    assert summary["by_status"] == {"500": 1} and summary["top_client_ips"][0]["ip"] == "203.0.113.7"
    assert start < entry["time"]


def test_playbooks():
    expected = {"alb-5xx-spike", "alb-unhealthy-host", "ec2-status-check-failed", "redis-connections-lost",
                "redis-memory-high", "ec2-cpu-credit-low", "rds-low-storage"}
    assert set(PLAYBOOKS) == expected, set(PLAYBOOKS) ^ expected
    tool_names = {t["name"] for t in TOOL_SPECS}
    for name, pb in PLAYBOOKS.items():
        for tool, args in pb["prefetch"]:
            assert tool in tool_names, f"{name}: 없는 도구 {tool}"
            if tool == "query_metrics":
                assert args["metric"] in METRIC_SPECS, f"{name}: 없는 지표 {args['metric']}"
    assert playbook_for("peakpic-alb-5xx-spike") is PLAYBOOKS["alb-5xx-spike"]
    assert playbook_for("unknown-alarm") is DEFAULT_PLAYBOOK


def test_tool_schemas_strict():
    for spec in TOOL_SPECS + [REPORT_TOOL]:
        schema = spec["input_schema"]
        assert spec.get("strict") is True and schema.get("additionalProperties") is False, spec["name"]
        assert set(schema.get("required", [])) == set(schema["properties"]), f"{spec['name']}: strict 는 모든 키가 required"


def test_handler_filters():
    assert should_analyze({"AlarmName": "x", "NewStateValue": "ALARM"})
    assert not should_analyze({"AlarmName": "x", "NewStateValue": "OK"})
    assert not should_analyze({"detail-type": "EC2 Auto Scaling Instance Refresh Succeeded"})
    assert not should_analyze({})
    t = parse_alarm_time("2026-08-22T06:12:34.567+0000")
    assert t.isoformat().startswith("2026-08-22T06:12:34"), t
    assert parse_alarm_time("2026-08-22T06:12:34Z").tzinfo is not None


def test_clean_report():
    cleaned = _clean_report({"category": "app_bug", "user_impact": "none", "headline": "h", "what_happened": "w",
                             "evidence": ["500 응답 26건", "느낌상 스캐너 같다"], "probable_causes": [],
                             "next_checks": [], "similar_past": ""})
    assert cleaned["evidence"] == ["500 응답 26건"] and cleaned["evidence_dropped"] == 1
    empty = _clean_report({"category": "app_bug", "user_impact": "full", "headline": "h", "what_happened": "w",
                           "evidence": ["수치 없음"], "probable_causes": [], "next_checks": [], "similar_past": ""})
    assert empty["category"] == "unknown" and empty["headline"].startswith("(근거 부족)")


class FakeClient:
    """대본대로 응답하는 모델. 1턴: query_logs 호출, 2턴: submit_report."""

    def __init__(self):
        self.calls = 0
        self.messages = SimpleNamespace(create=self.create)

    def create(self, **kwargs):
        self.calls += 1
        assert kwargs["tools"][-1]["name"] == "submit_report"
        assert kwargs["messages"][0]["role"] == "user" and "## 선조회 결과" in kwargs["messages"][0]["content"]
        usage = SimpleNamespace(input_tokens=1000, output_tokens=100)
        if self.calls == 1:
            block = SimpleNamespace(type="tool_use", id="t1", name="query_logs",
                                    input={"kind": "summary", "pattern": "", "window_minutes": 5})
            return SimpleNamespace(stop_reason="tool_use", content=[block], usage=usage)
        last = kwargs["messages"][-1]["content"]
        assert isinstance(last, list) and last[0]["type"] == "tool_result", last
        report = {"category": "external_scanner", "user_impact": "none", "headline": "스캐너 24건, 영향 없음",
                  "what_happened": "w", "evidence": ["500 응답 26건 중 24건이 POST /auth/callback"],
                  "probable_causes": ["외부 스캐너"], "next_checks": ["IP 차단 검토"], "similar_past": ""}
        block = SimpleNamespace(type="tool_use", id="t2", name="submit_report", input=report)
        return SimpleNamespace(stop_reason="tool_use", content=[block], usage=usage)


def test_investigate_loop_with_fake_model():
    case = json.loads((CASE_DIR / "2026-09-29-auth-callback-scanner.json").read_text())
    tools = FixtureTools(case["fixtures"])
    report, trace, prefetched = investigate(case["alarm"], tools, FakeClient(), model="fake", fallbacks=False)
    assert report and report["category"] == "external_scanner", report
    assert trace["steps"] == 2 and trace["input_tokens"] == 2000, trace
    assert trace["tool_calls"][:3] == ["query_logs", "query_access_logs", "recent_deploys"], trace["tool_calls"]
    assert trace["tool_calls"][-1] == "query_logs"
    embed = build_report_embed(case["alarm"], report, trace)
    assert embed["title"].startswith("🟢 분석") and any(f["name"] == "근거" for f in embed["fields"]), embed
    fallback = build_fallback_embed(case["alarm"], prefetched, trace, reason="테스트")
    assert fallback["fields"] and "요청" in fallback["fields"][1]["value"], fallback
    print(json.dumps(embed, ensure_ascii=False, indent=2))


def test_cases_well_formed():
    for path in sorted(CASE_DIR.glob("*.json")):
        case = json.loads(path.read_text())
        for key in ("alarm", "fixtures", "expect"):
            assert key in case, f"{path.name}: {key} 누락"
        assert should_analyze(case["alarm"]), path.name
        assert set(case["expect"]) >= {"category", "user_impact"}, path.name


def main():
    tests = [v for k, v in globals().items() if k.startswith("test_") and callable(v)]
    for test in tests:
        test()
        print(f"  ✓ {test.__name__}")
    print(f"\n{len(tests)}개 테스트 통과")


if __name__ == "__main__":
    sys.exit(main())
