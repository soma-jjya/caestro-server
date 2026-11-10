"""로컬 검증: AWS·LLM 없이 마스킹, 액세스 로그 파서, 플레이북, 임베드, 조사 루프(가짜 모델)를 확인한다.

    python3 local_test.py
"""

import json
import sys
import urllib.error
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

# 알람 이름표는 alert-notify 와 같은 파일을 쓴다. 배포 패키지에는 setup-analyze.sh 가 복사해 넣는다 (#254)
sys.path.append(str(Path(__file__).resolve().parent.parent / "alert-notify"))  # 뒤에 붙인다: handler.py 이름이 겹친다

from fixtures import FixtureTools
import github_issues
import handler
from github_issues import GitHubIssues, sync
from handler import parse_alarm_time, post_discord, should_analyze
from triage import comment_body, decide, fingerprint, issue_body, normalize_path, top_exception
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


def test_discord_post_sets_user_agent():
    # 기본 python-urllib UA 는 Discord(Cloudflare)가 403 code 1010 으로 막는다 — 연기 테스트에서 실제로 겪음
    seen = {}

    class FakeResp:
        status = 204
        def __enter__(self): return self
        def __exit__(self, *a): return False

    def fake_urlopen(req, timeout=0):
        seen["ua"] = req.get_header("User-agent")
        return FakeResp()

    original = handler.urllib.request.urlopen
    handler.urllib.request.urlopen = fake_urlopen
    try:
        assert post_discord("https://example.invalid/hook", {"title": "t"}) == 204
    finally:
        handler.urllib.request.urlopen = original
    assert seen["ua"] and "urllib" not in seen["ua"].lower(), seen


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
    assert embed["title"] == "🔎 분석 · 서버 오류(5xx) 급증 · 앱 (B2C)", embed["title"]
    assert embed["description"].startswith("**") and "🟢 사용자 영향 없음 · 외부 스캐너·봇" in embed["description"], embed
    assert any(f["name"] == "근거" for f in embed["fields"]), embed
    assert "peakpic-alb-5xx-spike" in embed["footer"]["text"] and "도구" in embed["footer"]["text"], embed["footer"]
    fallback = build_fallback_embed(case["alarm"], prefetched, trace, reason="테스트")
    assert fallback["title"] == "⚪ 분석 못 함 · 서버 오류(5xx) 급증 · 앱 (B2C)", fallback["title"]
    assert fallback["fields"] and "요청" in fallback["fields"][1]["value"], fallback
    assert fallback["fields"][1]["name"] == "접근 기록 (ALB)", fallback["fields"][1]["name"]
    print(json.dumps(embed, ensure_ascii=False, indent=2))


def test_cases_well_formed():
    for path in sorted(CASE_DIR.glob("*.json")):
        case = json.loads(path.read_text())
        for key in ("alarm", "fixtures", "expect"):
            assert key in case, f"{path.name}: {key} 누락"
        assert should_analyze(case["alarm"]), path.name
        assert set(case["expect"]) >= {"category", "user_impact"}, path.name
        assert isinstance(case["expect"].get("issue"), bool), f"{path.name}: expect.issue(생성 여부) 누락"


# ── 이슈 자동 생성 (#233) ─────────────────────────────────────────────────────

def _startup_case():
    """9/1 기동 실패: 우리 코드 예외가 스택에 있는 유일한 골든 케이스. 보고서는 사람 결론을 흉내 낸다."""
    case = json.loads((CASE_DIR / "2026-09-01-release-111-startup-failure.json").read_text())
    tools = FixtureTools(case["fixtures"])
    observations = [{"tool": "query_logs", "args": {}, "result": tools.query_logs("errors")},
                    {"tool": "query_access_logs", "args": {}, "result": tools.query_access_logs(500)}]
    report = {"category": "deploy_regression", "user_impact": "none",
              "headline": "#111 릴리스 새 인스턴스 기동 실패, 구버전 1대 유지",
              "what_happened": "w", "evidence": ["기동 실패 로그 3건", "언헬시 호스트 1대 (헬시 1대 유지)"],
              "probable_causes": ["TargetLifecycleWatcher 생성자 주입 누락"], "next_checks": ["#111 롤백 여부"], "similar_past": ""}
    return case, report, observations


def test_issue_gate():
    case, report, obs = _startup_case()
    verdict = decide(case["alarm"], report, obs, parse_alarm_time(case["alarm"]["StateChangeTime"]))
    assert verdict["ok"], verdict
    draft = verdict["draft"]
    exc = draft["exception"]
    assert exc["exception"] == "org.springframework.beans.factory.UnsatisfiedDependencyException", exc
    assert exc["count"] == 3 and 1 <= len(exc["frames"]) <= 3 and all("com.caestro" in f for f in exc["frames"]), exc
    assert draft["title"].startswith("[자동] ") and draft["when"].endswith("UTC") and len(draft["fingerprint"]) == 8, draft
    # 1. 분류가 아니면
    out = decide(case["alarm"], {**report, "category": "external_scanner"}, obs)
    assert not out["ok"] and out["reasons"] == ["분류가 외부 스캐너·봇"], out
    # 2. 근거 1건
    out = decide(case["alarm"], {**report, "evidence": ["3건"]}, obs)
    assert not out["ok"] and out["reasons"] == ["근거 1건 (2건 필요)"], out
    # 3. 우리 코드 예외 없음 — 스캐너 케이스의 로그(NoResourceFoundException, 스프링 프레임만)
    scanner = json.loads((CASE_DIR / "2026-09-18-scanner-wave.json").read_text())
    scanner_obs = [{"tool": "query_logs", "args": {}, "result": FixtureTools(scanner["fixtures"]).query_logs("errors")}]
    out = decide(scanner["alarm"], report, scanner_obs)
    assert not out["ok"] and out["reasons"] == ["우리 코드 예외 없음"], out
    # 셋 다 틀리면 이유 세 개
    out = decide(scanner["alarm"], {**report, "category": "unknown", "evidence": []}, scanner_obs)
    assert len(out["reasons"]) == 3, out


def test_issue_fingerprint():
    assert normalize_path("/users/123/shots/9f1c2d3e4a5b6c7d8e9f0a1b2c3d4e5f?x=1") == "/users/{n}/shots/{id}"
    assert normalize_path("/sessions/550e8400-e29b-41d4-a716-446655440000/join") == "/sessions/{id}/join"
    assert normalize_path("") == ""
    same_a = fingerprint("peakpic-alb-5xx-spike", "java.lang.NullPointerException", normalize_path("/users/1"))
    same_b = fingerprint("peakpic-alb-5xx-spike", "java.lang.NullPointerException", normalize_path("/users/2"))
    other = fingerprint("peakpic-alb-unhealthy-host", "java.lang.NullPointerException", normalize_path("/users/1"))
    assert same_a == same_b and same_a != other and len(same_a) == 8
    assert top_exception("java.lang.IllegalStateException: boom\n\tat x") == "java.lang.IllegalStateException"
    assert top_exception("Application run failed") == ""


def test_issue_body_has_no_secrets():
    case, report, obs = _startup_case()
    report = {**report,
              "evidence": ["IP 203.0.113.7 에서 500 응답 26건", "계정 123456789012 의 로그 그룹 /aws/ec2/peakpic-app 에 3건"],
              "next_checks": ['UA "Mozilla/5.0 (compatible; zgrab/0.x)" 차단', "user a@b.co 문의 010-1234-5678",
                              "버킷 arn:aws:s3:::peakpic-alb-logs-000000000000 확인"]}
    draft = decide(case["alarm"], report, obs)["draft"]
    secrets = ["/aws/ec2/peakpic-app", "peakpic-alb-logs-000000000000", "", "app/peakpic-alb/abc"]
    body = issue_body(draft, secrets)
    for leak in ("203.0.113.7", "123456789012", "/aws/ec2/peakpic-app", "Mozilla", "zgrab", "a@b.co", "1234-5678", "arn:aws"):
        assert leak not in body, f"본문 누출: {leak}\n{body}"
    for mark in ("[IP]", "[ACCOUNT]", "[REDACTED]", "[UA]", "[EMAIL]", "[PHONE]"):
        assert mark in body, f"{mark} 없음\n{body}"
    assert "500 응답 26건" in body and "3건" in body  # 수치는 남는다
    assert body.rstrip().endswith(f"지문 `fp:{draft['fingerprint']}`"), body[-80:]
    assert "자동 생성 · 사람 확인 필요" in body and "auto-triage:false-positive" in body and "- [ ] " in body
    assert "`org.springframework.beans.factory.UnsatisfiedDependencyException` — 분석 창 안 에러 로그 3건" in body
    comment = comment_body(3, draft, secrets)
    assert comment.startswith("**3회째** · ") and "[IP]" in comment and f"fp:{draft['fingerprint']}" in comment, comment


class FakeGitHub:
    """GitHubIssues 대역: 대본(found)대로 찾고, 쓰기는 기록만 한다."""

    def __init__(self, found=()):
        self.found = list(found)
        self.calls = []

    def find_by_fingerprint(self, fp):
        self.calls.append(("search", fp))
        return self.found

    def create(self, title, body, labels):
        self.calls.append(("create", title, labels))
        return {"number": 301, "html_url": "https://github.com/x/y/issues/301"}

    def comment(self, number, body):
        self.calls.append(("comment", number, body[:12]))
        return {"html_url": f"https://github.com/x/y/issues/{number}#c1"}

    def count_marker_comments(self, number, fp):
        self.calls.append(("count", number))
        return 1


def test_issue_sync_modes():
    case, report, obs = _startup_case()
    draft = decide(case["alarm"], report, obs)["draft"]
    # shadow: 찾기만, 쓰기 없음
    gh = FakeGitHub()
    out = sync(gh, draft, "shadow")
    assert out["action"] == "shadow" and "새 이슈" in out["note"] and [c[0] for c in gh.calls] == ["search"], (out, gh.calls)
    # on, 같은 지문 없음 → 생성, 라벨 2개
    gh = FakeGitHub()
    out = sync(gh, draft, "on")
    assert out["action"] == "created" and out["number"] == 301 and gh.calls[-1][2] == ["auto-triage", "bug"], (out, gh.calls)
    # on, 열린 이슈 있음 → 코멘트. 본문이 1회째, 기존 표식 코멘트 1개 → 3회째
    existing = [{"number": 120, "state": "open", "html_url": "https://github.com/x/y/issues/120", "labels": ["auto-triage", "bug"]}]
    gh = FakeGitHub(existing)
    out = sync(gh, draft, "on")
    assert out["action"] == "commented" and out["number"] == 120 and out["nth"] == 3, out
    assert gh.calls[-1][0] == "comment" and gh.calls[-1][2].startswith("**3회째**"), gh.calls
    # shadow, 열린 이슈 있음 → '코멘트했을 것'
    gh = FakeGitHub(existing)
    assert "#120 에 코멘트" in sync(gh, draft, "shadow")["note"]
    # 닫힌 이슈만 있음 → 새로 만든다
    gh = FakeGitHub([{**existing[0], "state": "closed"}])
    assert sync(gh, draft, "on")["action"] == "created"
    # 오탐 라벨이 달린 지문 → 어느 모드든 아무것도 안 한다
    flagged = [{**existing[0], "state": "closed", "labels": ["auto-triage", "auto-triage:false-positive"]}]
    for mode in ("on", "shadow"):
        gh = FakeGitHub(flagged)
        out = sync(gh, draft, mode)
        assert out["action"] == "skipped" and [c[0] for c in gh.calls] == ["search"], (mode, out, gh.calls)


def test_handler_triage_never_raises():
    case, report, obs = _startup_case()
    when = parse_alarm_time(case["alarm"]["StateChangeTime"])
    trace = {"tool_calls": [], "elapsed_s": 1}
    original = github_issues.GitHubIssues
    github_issues.GitHubIssues = lambda repo, token="": FakeGitHub()
    try:
        # 기본(shadow): 섀도 칸이 붙는다
        embed = build_report_embed(case["alarm"], report, trace)
        out = handler.triage_issue(case["alarm"], report, obs, when, embed, env={"GITHUB_REPO": "x/y"})
        assert out["action"] == "shadow" and embed["fields"][-1]["name"] == "📝 이슈 (섀도)", (out, embed["fields"][-1])
        assert embed["fields"][-1]["value"].startswith("만들 것 · [자동] "), embed["fields"][-1]
        # 문지기 탈락: shadow 는 이유를 칸으로 남기고, on 은 칸을 붙이지 않는다
        embed = build_report_embed(case["alarm"], report, trace)
        base = len(embed["fields"])
        out = handler.triage_issue(case["alarm"], {**report, "category": "external_scanner"}, obs, when, embed,
                                   env={"GITHUB_REPO": "x/y"})
        assert out["action"] == "gate" and embed["fields"][-1]["value"] == "만들지 않음 · 분류가 외부 스캐너·봇", embed["fields"][-1]
        embed = build_report_embed(case["alarm"], report, trace)
        out = handler.triage_issue(case["alarm"], {**report, "category": "external_scanner"}, obs, when, embed,
                                   env={"GITHUB_REPO": "x/y", "ISSUE_CREATE": "on", "GITHUB_TOKEN": "t"})
        assert out["action"] == "gate" and len(embed["fields"]) == base, embed["fields"]
        # on 인데 토큰이 없다 → '실패' 칸, 예외는 밖으로 안 나온다
        embed = build_report_embed(case["alarm"], report, trace)
        out = handler.triage_issue(case["alarm"], report, obs, when, embed, env={"GITHUB_REPO": "x/y", "ISSUE_CREATE": "on"})
        assert out["action"] == "failed" and embed["fields"][-1]["value"].startswith("실패 · "), embed["fields"][-1]
        # on + 토큰 → 생성, 링크 칸
        embed = build_report_embed(case["alarm"], report, trace)
        out = handler.triage_issue(case["alarm"], report, obs, when, embed, env={"GITHUB_REPO": "x/y", "ISSUE_CREATE": "on", "GITHUB_TOKEN": "t"})
        assert out["action"] == "created" and embed["fields"][-1] == {"name": "📝 이슈", "value": "[#301](https://github.com/x/y/issues/301) 새로 만듦"}, embed["fields"][-1]
        # off → 아무것도
        embed = build_report_embed(case["alarm"], report, trace)
        assert handler.triage_issue(case["alarm"], report, obs, when, embed, env={"ISSUE_CREATE": "off"}) is None
        assert len(embed["fields"]) == base
    finally:
        github_issues.GitHubIssues = original


def test_github_client_requests():
    """실제 HTTP 없이 요청 모양만 본다: 주소·헤더·본문·오류 변환."""
    seen = []

    class FakeResp:
        def __init__(self, payload): self.payload = payload
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def read(self): return json.dumps(self.payload).encode()

    def fake_urlopen(req, timeout=0):
        seen.append(req)
        if "/search/issues" in req.full_url:
            return FakeResp({"items": [{"number": 7, "state": "open", "html_url": "u", "labels": [{"name": "auto-triage"}]}]})
        if "/comments?" in req.full_url and req.get_method() == "GET":
            return FakeResp([{"body": "**2회째** … 지문 `fp:deadbeef`"}, {"body": "사람 코멘트"}])
        if req.full_url.endswith("/issues") and req.get_method() == "POST":
            return FakeResp({"number": 8, "html_url": "u8"})
        raise urllib.error.HTTPError(req.full_url, 403, "forbidden", {}, None)

    original = github_issues.urllib.request.urlopen
    github_issues.urllib.request.urlopen = fake_urlopen
    try:
        gh = GitHubIssues("x/y", token="tok")
        found = gh.find_by_fingerprint("deadbeef")
        assert found == [{"number": 7, "state": "open", "html_url": "u", "labels": ["auto-triage"]}], found
        assert "repo%3Ax/y" in seen[-1].full_url and "%22fp%3Adeadbeef%22" in seen[-1].full_url, seen[-1].full_url
        assert seen[-1].get_header("Authorization") == "Bearer tok" and "urllib" not in seen[-1].get_header("User-agent").lower()
        assert gh.count_marker_comments(7, "deadbeef") == 1
        assert gh.create("t", "b", ["auto-triage"]) == {"number": 8, "html_url": "u8"}
        assert json.loads(seen[-1].data) == {"title": "t", "body": "b", "labels": ["auto-triage"]}
        assert GitHubIssues("x/y").find_by_fingerprint("a") and seen[-1].get_header("Authorization") is None  # 토큰 없이도 검색
        try:
            gh.comment(7, "b")
            raise AssertionError("403 이 RuntimeError 로 바뀌어야 한다")
        except RuntimeError as e:
            assert "403" in str(e) and "comments" in str(e), e
    finally:
        github_issues.urllib.request.urlopen = original


def main():
    tests = [v for k, v in globals().items() if k.startswith("test_") and callable(v)]
    for test in tests:
        test()
        print(f"  ✓ {test.__name__}")
    print(f"\n{len(tests)}개 테스트 통과")


if __name__ == "__main__":
    sys.exit(main())
