"""SNS로 들어온 알림을 Discord 임베드로 변환하는 순수 로직 (AWS SDK 무관, 로컬 테스트 가능).

원칙(#249): 제목은 사람 말, 숫자는 지표 단위로, 해제는 한 줄, 시스템 이름은 footer 로 내린다.
"""

import json
import re
from datetime import datetime, timedelta, timezone

from alarm_catalog import ALARM_CATALOG, B2C_SERVICE, classify_alarm

KST = timezone(timedelta(hours=9))

COLOR_RED = 15158332
COLOR_GREEN = 3066993
COLOR_AMBER = 16098851
COLOR_GRAY = 9807270

def _pct(value):
    return f"{value:.0f}%"


def _gib(value):
    return f"{value / 1024 ** 3:.1f}GiB"


def _count(unit):
    return lambda value: f"{int(round(value))}{unit}"


# 지표 → (짧은 이름, 값 표기). 알람 메시지의 Threshold·NewStateReason 숫자를 사람 단위로 바꾼다.
METRICS = {
    "UnHealthyHostCount": ("실패 서버", _count("대")),
    "StatusCheckFailed": ("검사 실패", _count("대")),
    "CurrConnections": ("Redis 연결", _count("개")),
    "FreeStorageSpace": ("DB 여유 공간", _gib),
    "CPUCreditBalance": ("크레딧 잔고", lambda v: f"{v:g}"),
    "CPUUtilization": ("CPU", _pct),
    "HTTPCode_Target_5XX_Count": ("5xx", _count("건")),
    "HTTPCode_ELB_5XX_Count": ("5xx", _count("건")),
    "mem_used_percent": ("메모리", _pct),
    "DatabaseMemoryUsagePercentage": ("Redis 메모리", _pct),
}

COMPARISON_WORD = {
    "GreaterThanThreshold": "초과",
    "GreaterThanOrEqualToThreshold": "이상",
    "LessThanThreshold": "미만",
    "LessThanOrEqualToThreshold": "이하",
}

# ASG 인스턴스 새로고침 이벤트 → (아이콘, 색, 제목, 설명)
# deploy.yml 이 '배포 시작'을 알리므로 여기서는 그 뒤 실제 교체 결과만 알린다 (짝을 이루는 후반부)
REFRESH_STATE = {
    "EC2 Auto Scaling Instance Refresh Succeeded": (
        "✅", COLOR_GREEN, "배포 반영 완료",
        "새 버전이 모든 서버에 올라갔어요. 이제 확인해 봐도 돼요.",
    ),
    "EC2 Auto Scaling Instance Refresh Failed": (
        "❌", COLOR_RED, "배포 반영 실패",
        "서버 교체가 실패했어요. 새 버전이 올라가지 않았을 수 있어요. ASG 콘솔에서 원인(헬스체크 실패·용량 부족)을 확인하세요.",
    ),
    "EC2 Auto Scaling Instance Refresh Cancelled": (
        "⚠️", COLOR_AMBER, "배포 반영 취소",
        "교체가 중간에 멈췄어요. 어떤 버전이 돌고 있는지 확인하세요.",
    ),
}


def should_notify(message):
    """이 메시지를 Discord 에 보낼지 판단한다. (보낼지, 건너뛰는 이유)

    알람 메시지만 거른다: 발생(→ALARM)과 진짜 해제(ALARM→OK)만 보낸다.
    서버 생성·교체 때 생기는 '데이터 부족→정상'과 '→데이터 부족'은 아무 일도 아니므로 보내지 않는다.
    """
    if not _is_alarm(message):
        return True, ""
    new, old = message.get("NewStateValue"), message.get("OldStateValue")
    if new == "ALARM":
        return True, ""
    if new == "OK":
        if old in (None, "ALARM"):
            return True, ""
        return False, f"{old}→OK 는 해제가 아니라 알리지 않음"
    return False, f"→{new} 전이는 알리지 않음"


def build_embed(message, region="ap-northeast-2"):
    """알림 메시지(dict)를 Discord 임베드로 변환한다. 형태를 판별해 적절한 포맷터로 위임."""
    if _is_alarm(message):
        return _alarm_embed(message, region)
    if str(message.get("detail-type", "")) in REFRESH_STATE:
        return _refresh_embed(message)
    return _unknown_embed(message)


def _is_alarm(message):
    return "AlarmName" in message and "NewStateValue" in message


def _alarm_embed(message, region):
    """CloudWatch 알람 상태 변경. 발생은 현재·기준·지속 3칸, 해제는 한 줄."""
    name = message.get("AlarmName", "(이름 없음)")
    service, suffix = classify_alarm(name)
    title, what, impact = ALARM_CATALOG.get(suffix, (name, message.get("AlarmDescription") or "", ""))
    headline = f"{title} · {service}" if service else title
    url = f"https://{region}.console.aws.amazon.com/cloudwatch/home?region={region}#alarmsV2:alarm/{name}"
    when = _discord_time(message.get("StateChangeTime"))
    state = message.get("NewStateValue")

    if state == "OK":
        return {
            "title": f"✅ 해제 · {headline}"[:256],
            "description": f"정상으로 돌아왔어요. {when}".strip(),
            "color": COLOR_GREEN,
            "url": url,
            "footer": {"text": name},
        }

    icon, color = ("🚨", COLOR_RED) if state == "ALARM" else ("❔", COLOR_GRAY)
    description = "\n".join(line for line in (what, impact) if line)
    embed = {
        "title": f"{icon} {headline}"[:256],
        "description": f"{description}\n\n[CloudWatch 에서 보기]({url})"[:2000],
        "color": color,
        "url": url,
        "fields": [],
        "footer": {"text": name},
    }
    if message.get("AlarmRule"):
        embed["fields"].append({"name": "조건", "value": _rule_text(message["AlarmRule"])[:1024]})
    else:
        embed["fields"].extend(_condition_fields(message.get("Trigger") or {}, message.get("NewStateReason") or ""))
    if when:
        embed["fields"].append({"name": "시각", "value": when, "inline": True})
    return embed


def _condition_fields(trigger, reason):
    """Trigger + NewStateReason → 현재 / 기준 / 지속 세 칸. 지표를 모르면 AWS 원문 한 줄로 대신한다."""
    metric = trigger.get("MetricName")
    if not metric:
        return [{"name": "판정 근거", "value": reason[:1024]}] if reason else []
    label, fmt = METRICS.get(metric, (metric, lambda v: f"{v:g}"))
    fields = []

    current = _latest_datapoint(reason)
    if current is not None:
        fields.append({"name": f"현재 {label}", "value": fmt(current), "inline": True})

    threshold = trigger.get("Threshold")
    word = COMPARISON_WORD.get(trigger.get("ComparisonOperator"), "")
    if threshold is not None:
        fields.append({"name": "기준", "value": f"{fmt(float(threshold))} {word}".strip(), "inline": True})

    period, evals = trigger.get("Period"), trigger.get("EvaluationPeriods")
    if period and evals:
        minutes = int(period) * int(evals) // 60
        fields.append({"name": "지속", "value": f"{minutes}분 연속" if int(evals) > 1 else f"{minutes}분", "inline": True})
    return fields


def _latest_datapoint(reason):
    """'Threshold Crossed: 2 datapoints [1.0 (08/11/26 06:12:00), 1.0 (...)] were ...' 에서 가장 최근 값 하나를 뽑는다."""
    match = re.search(r"\[([^\]]+)\]", reason or "")
    if not match:
        return None
    numbers = re.findall(r"(-?\d+(?:\.\d+)?(?:E-?\d+)?)(?:\s*\([^)]*\))?", match.group(1))
    try:
        return float(numbers[0]) if numbers else None
    except ValueError:
        return None


def _rule_text(rule):
    """복합 알람 규칙 'ALARM("a") AND ALARM("b")' 의 알람 이름을 사람 말 제목으로 바꾼다 (#231)."""

    def replace(match):
        _, suffix = classify_alarm(match.group(1))
        title = ALARM_CATALOG.get(suffix, (match.group(1),))[0]
        return f"「{title}」"

    text = re.sub(r'ALARM\("([^"]+)"\)', replace, rule)
    return text.replace(" AND ", " 그리고 ").replace(" OR ", " 또는 ")


def _refresh_embed(message):
    """ASG 인스턴스 새로고침 결과 — 배포의 '반영 완료' 시점을 알린다."""
    detail = message.get("detail") or {}
    icon, color, title, note = REFRESH_STATE[message["detail-type"]]
    asg = detail.get("AutoScalingGroupName", "")
    if asg == "peakpic-asg":
        service = B2C_SERVICE
    elif asg.startswith("pp-b2b-"):
        service = f"축제 서버 ({asg})"
    else:
        service = asg
    when = _discord_time(message.get("time"))
    footer = " · ".join(part for part in (asg, detail.get("InstanceRefreshId")) if part)
    embed = {
        "title": f"{icon} {title} · {service}"[:256] if service else f"{icon} {title}",
        "description": f"{note} {when}".strip(),
        "color": color,
    }
    if footer:
        embed["footer"] = {"text": footer}
    return embed


def _unknown_embed(message):
    """예상 못 한 형태 — 원문을 잘라서라도 전달한다 (알림 유실 방지)."""
    body = message.get("raw") if isinstance(message.get("raw"), str) else json.dumps(message, ensure_ascii=False)
    return {
        "title": "❔ 분류되지 않은 알림",
        "description": f"```{body[:1500]}```",
        "color": COLOR_GRAY,
    }


def _discord_time(iso_text):
    """ISO 시각 → Discord 시간 토큰 '<t:…:f> (<t:…:R>)'. 보는 사람 시간대로 '11월 9일 오후 2:31 (3분 전)' 처럼 그려진다."""
    parsed = _parse_time(iso_text)
    if not parsed:
        return str(iso_text or "")
    unix = int(parsed.timestamp())
    return f"<t:{unix}:f> (<t:{unix}:R>)"


def _kst(iso_text):
    """ISO 시각 문자열을 KST 표기로 변환한다 (로컬 미리보기용). 파싱 실패 시 원문 그대로."""
    parsed = _parse_time(iso_text)
    return parsed.astimezone(KST).strftime("%Y-%m-%d %H:%M:%S KST") if parsed else str(iso_text or "")


def _parse_time(iso_text):
    if not iso_text:
        return None
    try:
        return datetime.fromisoformat(str(iso_text).replace("Z", "+00:00"))
    except ValueError:
        return None
