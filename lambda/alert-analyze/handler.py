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
from report import build_fallback_embed, build_report_embed

_api_key_cache = None


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

        tools = AwsTools(parse_alarm_time(alarm.get("StateChangeTime")))
        client = anthropic.Anthropic(api_key=_api_key(), max_retries=1, timeout=60.0)
        report, trace, prefetched = investigate(alarm, tools, client, model=model,
                                               fallbacks=os.environ.get("FALLBACKS", "on") != "off")
        if report is None:
            embed = build_fallback_embed(alarm, prefetched, trace, reason=f"stop={trace.get('stop')}")
        else:
            embed = build_report_embed(alarm, report, trace)
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
    if _api_key_cache:
        return _api_key_cache
    key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if not key and os.environ.get("ANTHROPIC_SECRET_ID"):
        import boto3

        value = boto3.client("secretsmanager").get_secret_value(SecretId=os.environ["ANTHROPIC_SECRET_ID"])["SecretString"]
        try:
            parsed = json.loads(value)
            key = parsed.get("ANTHROPIC_API_KEY") or parsed.get("api_key") or ""
        except ValueError:
            key = value.strip()
    if not key:
        raise RuntimeError("ANTHROPIC_API_KEY 또는 ANTHROPIC_SECRET_ID 가 필요하다")
    _api_key_cache = key
    return key
