"""Lambda 진입점. SNS 로 들어온 CloudWatch 알람(ALARM 상태만)을 조사해 Discord 에 '분석' 임베드를 올린다.

기존 peakpic-alert-notify 는 알람을 즉시 전달하고, 이 함수는 같은 토픽을 구독해 1~2분 뒤 분석을 따라 붙인다.
어떤 경우에도 예외를 밖으로 던지지 않는다 — 비동기 재시도로 같은 분석이 두 번 올라가는 것을 막기 위해서다.
"""

import json
import os
import re
import urllib.request
from datetime import datetime, timezone

from investigate import DEFAULT_MODEL, investigate
from report import add_issue_field, build_fallback_embed, build_report_embed

_api_key_cache = None
_github_token_cache = None
# 이슈 본문에서 지울 인프라 이름(공개 레포): 이 환경변수들의 값
INFRA_ENV_KEYS = ("LOG_GROUP", "ALB_LOG_BUCKET", "ALB_LOG_PREFIX", "ALB_DIM", "TG_DIM", "ASG_NAME", "CACHE_ID", "RDS_ID")


def lambda_handler(event, context):
    posted = 0
    for record in event.get("Records", []):
        message = _parse_message(record)
        if not should_analyze(message):
            continue
        posted += _analyze_and_post(message)
    return {"analyzed": posted}


def should_analyze(message):
    """CloudWatch 알람의 ALARM 전이만 조사한다. OK·INSUFFICIENT_DATA·배포 이벤트·수동 메시지는 건너뛴다."""
    return bool(message) and "AlarmName" in message and message.get("NewStateValue") == "ALARM"


def parse_alarm_time(text):
    """CloudWatch 가 주는 '2026-08-22T06:12:34.567+0000' 을 aware datetime 으로. 실패하면 지금 시각."""
    if not text:
        return datetime.now(timezone.utc)
    normalized = re.sub(r"([+-]\d{2})(\d{2})$", r"\1:\2", str(text).replace("Z", "+00:00"))
    try:
        return datetime.fromisoformat(normalized).astimezone(timezone.utc)
    except ValueError:
        return datetime.now(timezone.utc)


def _analyze_and_post(alarm):
    hook = os.environ.get("DISCORD_WEBHOOK_URL", "")
    model = os.environ.get("MODEL", DEFAULT_MODEL)
    trace = {"model": model, "tool_calls": [], "input_tokens": 0, "output_tokens": 0, "elapsed_s": 0}
    prefetched = []
    try:
        from tools import AwsTools
        import anthropic

        when = parse_alarm_time(alarm.get("StateChangeTime"))
        tools = AwsTools(when)
        client = anthropic.Anthropic(api_key=_api_key(), max_retries=1, timeout=60.0)
        report, trace, prefetched = investigate(alarm, tools, client, model=model,
                                               fallbacks=os.environ.get("FALLBACKS", "on") != "off")
        if report is None:
            embed = build_fallback_embed(alarm, prefetched, trace, reason=f"stop={trace.get('stop')}")
        else:
            embed = build_report_embed(alarm, report, trace)
            triage_issue(alarm, report, prefetched, when, embed)
    except Exception as e:  # noqa: BLE001 — 분석 실패를 알림으로 남기고 끝낸다
        print(f"[alert-analyze] failed: {type(e).__name__}: {e}")
        embed = build_fallback_embed(alarm, prefetched, trace, reason=f"{type(e).__name__}")
    print(json.dumps({"alarm": alarm.get("AlarmName"), "trace": trace}, ensure_ascii=False, default=str))
    if not hook:
        print("[alert-analyze] DISCORD_WEBHOOK_URL 없음 — 임베드 출력만")
        print(json.dumps(embed, ensure_ascii=False))
        return 0
    status = post_discord(hook, embed)
    print(f"[alert-analyze] discord {status}")
    return 1 if 200 <= status < 300 else 0


def triage_issue(alarm, report, observations, when, embed, env=None):
    """결론이 앱 결함·배포 회귀면 GitHub 이슈로(#233). ISSUE_CREATE=off|shadow(기본)|on.
    어떤 실패도 분석 알림을 막지 않는다 — 임베드에 '실패' 한 줄로 남기고 끝낸다."""
    env = os.environ if env is None else env
    mode = (env.get("ISSUE_CREATE") or "shadow").strip().lower()
    if mode == "off":
        return None
    from github_issues import GitHubIssues, sync
    from triage import decide

    try:
        verdict = decide(alarm, report, observations, when)
        if not verdict["ok"]:
            outcome = {"action": "gate", "note": " · ".join(verdict["reasons"])}
            print(f"[alert-analyze] issue gate: {outcome['note']}")
            if mode == "shadow":
                add_issue_field(embed, outcome, mode)
            return outcome
        draft = verdict["draft"]
        repo = env.get("GITHUB_REPO", "")
        if not repo:
            raise RuntimeError("GITHUB_REPO 없음")
        token = _github_token(env) if mode == "on" else (env.get("GITHUB_TOKEN") or "")
        if mode == "on" and not token:
            raise RuntimeError("GITHUB_TOKEN 또는 GITHUB_SECRET_ID 없음")
        secrets = [env.get(k) or "" for k in INFRA_ENV_KEYS]
        outcome = sync(GitHubIssues(repo, token), draft, mode, secrets)
        outcome["title"] = draft["title"]
        outcome["fingerprint"] = draft["fingerprint"]
    except Exception as e:  # noqa: BLE001
        outcome = {"action": "failed", "note": f"{type(e).__name__}: {e}"[:200]}
    print(f"[alert-analyze] issue {outcome['action']}: {outcome.get('note') or outcome.get('url') or ''}")
    add_issue_field(embed, outcome, mode)
    return outcome


def post_discord(hook, embed):
    body = json.dumps({"embeds": [embed]}, ensure_ascii=False).encode("utf-8")
    # User-Agent 필수: 기본 python-urllib UA 는 Discord(Cloudflare)가 403 code 1010 으로 차단한다 (alert-notify 와 같은 사고)
    req = urllib.request.Request(hook, data=body, method="POST",
                                 headers={"Content-Type": "application/json", "User-Agent": "peakpic-alert-analyze/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        print(f"[alert-analyze] discord HTTPError {e.code}: {e.read()[:300]!r}")
        return e.code
    except Exception as e:  # noqa: BLE001
        print(f"[alert-analyze] discord failed: {type(e).__name__}")
        return 0


def _parse_message(record):
    raw = (record.get("Sns") or {}).get("Message", "")
    try:
        parsed = json.loads(raw)
        return parsed if isinstance(parsed, dict) else {}
    except (TypeError, ValueError):
        return {}


def _api_key():
    """ANTHROPIC_API_KEY 환경변수 또는 Secrets Manager(ANTHROPIC_SECRET_ID). 실행 중 1회만 읽는다."""
    global _api_key_cache
    if not _api_key_cache:
        _api_key_cache = _secret(os.environ, "ANTHROPIC_API_KEY", "ANTHROPIC_SECRET_ID", ("ANTHROPIC_API_KEY", "api_key"))
    if not _api_key_cache:
        raise RuntimeError("ANTHROPIC_API_KEY 또는 ANTHROPIC_SECRET_ID 가 필요하다")
    return _api_key_cache


def _github_token(env):
    """GITHUB_TOKEN 환경변수 또는 Secrets Manager(GITHUB_SECRET_ID). 없으면 빈 문자열 — 모드에 따라 호출자가 판단한다."""
    global _github_token_cache
    if not _github_token_cache:
        _github_token_cache = _secret(env, "GITHUB_TOKEN", "GITHUB_SECRET_ID", ("GITHUB_TOKEN", "token"))
    return _github_token_cache


def _secret(env, value_key, secret_id_key, json_keys):
    """환경변수 값이 있으면 그것을, 아니면 Secrets Manager 시크릿(평문 또는 JSON 의 json_keys 중 하나)을 읽는다."""
    value = (env.get(value_key) or "").strip()
    if value or not env.get(secret_id_key):
        return value
    import boto3

    raw = boto3.client("secretsmanager").get_secret_value(SecretId=env[secret_id_key])["SecretString"]
    try:
        parsed = json.loads(raw)
    except ValueError:
        return raw.strip()
    if not isinstance(parsed, dict):
        return raw.strip()
    return next((str(parsed[k]).strip() for k in json_keys if parsed.get(k)), "")
