"""SNS로 들어온 알림을 Discord 임베드로 변환하는 순수 로직 (AWS SDK 무관, 로컬 테스트 가능)."""

from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))

COLOR_RED = 15158332
COLOR_GREEN = 3066993
COLOR_AMBER = 16098851
COLOR_GRAY = 9807270

# CloudWatch 알람 상태 → (아이콘, 색, 한글 라벨)
ALARM_STATE = {
    "ALARM": ("🚨", COLOR_RED, "발생"),
    "OK": ("✅", COLOR_GREEN, "해제"),
    "INSUFFICIENT_DATA": ("⚠️", COLOR_AMBER, "데이터 부족"),
}

# ASG 인스턴스 새로고침 이벤트 → (아이콘, 색, 한글 라벨, 설명)
# deploy.yml이 '교체 시작'을 알리므로 여기서는 그 뒤 실제 교체 결과만 알린다 (짝을 이루는 후반부)
REFRESH_STATE = {
    "EC2 Auto Scaling Instance Refresh Succeeded": (
        "✅", COLOR_GREEN, "롤링 교체 완료",
        "새 버전이 모든 인스턴스에 반영됐다. 검증을 시작해도 된다.",
    ),
    "EC2 Auto Scaling Instance Refresh Failed": (
        "❌", COLOR_RED, "롤링 교체 실패",
        "인스턴스 교체가 실패했다 — 새 버전이 반영되지 않았을 수 있다. "
        "ASG 콘솔에서 원인(헬스체크 실패/용량 부족)을 확인할 것.",
    ),
    "EC2 Auto Scaling Instance Refresh Cancelled": (
        "⚠️", COLOR_AMBER, "롤링 교체 취소",
        "교체가 중단됐다. 반영 상태가 불확실하므로 실행 중인 이미지 버전을 확인할 것.",
    ),
}

COMPARISON = {
    "GreaterThanThreshold": ">",
    "GreaterThanOrEqualToThreshold": ">=",
    "LessThanThreshold": "<",
    "LessThanOrEqualToThreshold": "<=",
}


def build_embed(message, region="ap-northeast-2"):
    """알림 메시지(dict)를 Discord 임베드로 변환한다. 형태를 판별해 적절한 포맷터로 위임."""
    if "AlarmName" in message and "NewStateValue" in message:
        return _alarm_embed(message, region)
    if str(message.get("detail-type", "")) in REFRESH_STATE:
        return _refresh_embed(message, region)
    return _unknown_embed(message)


def _alarm_embed(message, region):
    """CloudWatch 알람 상태 변경 알림."""
    name = message.get("AlarmName", "(이름 없음)")
    icon, color, label = ALARM_STATE.get(message.get("NewStateValue"), ("❔", COLOR_GRAY, "알 수 없음"))

    embed = {
        "title": f"{icon} [{label}] {name}"[:256],
        "description": (message.get("AlarmDescription") or message.get("NewStateReason") or "")[:2000],
        "color": color,
        "url": f"https://{region}.console.aws.amazon.com/cloudwatch/home?region={region}#alarmsV2:alarm/{name}",
        "fields": [],
    }

    condition = _condition_text(message.get("Trigger") or {})
    if condition:
        embed["fields"].append({"name": "조건", "value": condition[:1024]})
    reason = message.get("NewStateReason")
    if reason and reason != embed["description"]:
        embed["fields"].append({"name": "판정 근거", "value": reason[:1024]})
    when = _kst(message.get("StateChangeTime"))
    if when:
        embed["fields"].append({"name": "시각", "value": when, "inline": True})
    return embed


def _condition_text(trigger):
    """Trigger 정보를 '지표 Statistic 비교 임계값 (N회 x M초)' 한 줄로 요약한다."""
    metric = trigger.get("MetricName")
    if not metric:
        return ""
    stat = trigger.get("Statistic", "").capitalize() or trigger.get("ExtendedStatistic", "")
    op = COMPARISON.get(trigger.get("ComparisonOperator"), trigger.get("ComparisonOperator", ""))
    threshold = trigger.get("Threshold")
    period = trigger.get("Period")
    evals = trigger.get("EvaluationPeriods")

    text = f"{trigger.get('Namespace', '')} {metric} ({stat}) {op} {threshold}".strip()
    if period and evals:
        text += f" — {evals}회 연속 × {period}초"
    dims = trigger.get("Dimensions") or []
    if dims:
        text += "\n" + ", ".join(f"{d.get('name')}={d.get('value')}" for d in dims)
    return text


def _refresh_embed(message, region):
    """ASG 인스턴스 새로고침 결과 — 배포의 '반영 완료' 시점을 알린다."""
    detail = message.get("detail") or {}
    icon, color, label, note = REFRESH_STATE[message["detail-type"]]
    asg = detail.get("AutoScalingGroupName", "(ASG 미상)")

    embed = {
        "title": f"{icon} {label} — {asg}"[:256],
        "description": note,
        "color": color,
        "fields": [],
    }
    if detail.get("InstanceRefreshId"):
        embed["fields"].append({"name": "새로고침 ID", "value": detail["InstanceRefreshId"], "inline": True})
    when = _kst(message.get("time"))
    if when:
        embed["fields"].append({"name": "시각", "value": when, "inline": True})
    return embed


def _unknown_embed(message):
    """예상 못 한 형태 — 원문을 잘라서라도 전달한다 (알림 유실 방지)."""
    import json

    body = message.get("raw") if isinstance(message.get("raw"), str) else json.dumps(message, ensure_ascii=False)
    return {
        "title": "❔ 분류되지 않은 알림",
        "description": f"```{body[:1500]}```",
        "color": COLOR_GRAY,
    }


def _kst(iso_text):
    """ISO 시각 문자열을 KST 표기로 변환한다. 파싱 실패 시 원문 그대로."""
    if not iso_text:
        return ""
    try:
        parsed = datetime.fromisoformat(str(iso_text).replace("Z", "+00:00"))
        return parsed.astimezone(KST).strftime("%Y-%m-%d %H:%M:%S KST")
    except ValueError:
        return str(iso_text)
