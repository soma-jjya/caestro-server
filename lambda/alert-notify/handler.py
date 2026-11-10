"""SNS(CloudWatch 알람 / EventBridge 배포 이벤트) → Discord 웹훅 중계 Lambda."""

import json
import os
import urllib.request

from format import build_embed, should_notify

TIMEOUT_SECONDS = 15


def lambda_handler(event, context):
    hook = os.environ.get("DISCORD_WEBHOOK_URL", "")
    if not hook:
        raise RuntimeError("DISCORD_WEBHOOK_URL 환경변수 미설정")

    region = os.environ.get("AWS_REGION", "ap-northeast-2")
    sent = skipped = 0
    for record in event.get("Records", []):
        sns = record.get("Sns") or {}
        message = _parse(sns.get("Message", ""), sns.get("Subject"))
        notify, reason = should_notify(message)
        if not notify:
            # 서버 생성·교체 때의 '데이터 부족→정상' 같은 전이는 채널만 길어지므로 기록만 남긴다 (#249)
            print(f"건너뜀 {message.get('AlarmName')}: {reason}")
            skipped += 1
            continue
        _post(hook, build_embed(message, region))
        sent += 1

    print(f"Discord 게시 완료: {sent}건, 건너뜀 {skipped}건")
    return {"sent": sent, "skipped": skipped}


def _parse(raw, subject):
    """SNS Message는 보통 JSON 문자열. 평문(수동 발행 등)이면 원문 그대로 감싸 전달한다."""
    try:
        parsed = json.loads(raw)
        return parsed if isinstance(parsed, dict) else {"raw": raw}
    except (json.JSONDecodeError, TypeError):
        return {"raw": f"{subject}\n{raw}" if subject else raw}


def _post(hook, embed):
    payload = json.dumps({"embeds": [embed]}, ensure_ascii=False).encode("utf-8")
    # User-Agent 필수: 기본 python-urllib UA는 Discord(Cloudflare)가 403으로 차단한다 (릴리스 알림에서 겪은 사고)
    request = urllib.request.Request(
        hook,
        data=payload,
        headers={"Content-Type": "application/json", "User-Agent": "peakpic-alert-notify/1.0"},
    )
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        return response.status
