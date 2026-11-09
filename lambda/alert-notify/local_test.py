"""로컬 검증: AWS 없이 보낼지 판단과 임베드 변환 결과를 확인한다.

    python3 local_test.py            # 사람이 읽는 미리보기 + 검증
    python3 local_test.py --json     # 임베드 JSON 그대로 출력
    python3 local_test.py --post     # DISCORD_WEBHOOK_URL 로 실제 발송 (footer 에 '시험 전송' 표시)
"""

import json
import os
import re
import sys

from format import _kst, build_embed, should_notify
from handler import _post

ALARM_FIRING = {
    "AlarmName": "peakpic-alb-unhealthy-host",
    "AlarmDescription": "ALB 대상 그룹에서 헬스체크에 실패한 인스턴스가 있다.",
    "NewStateValue": "ALARM",
    "OldStateValue": "OK",
    "NewStateReason": "Threshold Crossed: 5 datapoints [1.0 (22/08/26 06:12:00), 1.0 (22/08/26 06:11:00), 1.0 (22/08/26 06:10:00), 1.0 (22/08/26 06:09:00), 1.0 (22/08/26 06:08:00)] were greater than or equal to the threshold (1.0).",
    "StateChangeTime": "2026-08-22T06:12:34.567+0000",
    "Region": "Asia Pacific (Seoul)",
    "Trigger": {
        "MetricName": "UnHealthyHostCount",
        "Namespace": "AWS/ApplicationELB",
        "Statistic": "MAXIMUM",
        "Period": 60,
        "EvaluationPeriods": 5,
        "ComparisonOperator": "GreaterThanOrEqualToThreshold",
        "Threshold": 1.0,
        "Dimensions": [{"name": "TargetGroup", "value": "targetgroup/peakpic-tg/abc123"}],
    },
}

ALARM_RECOVERED = {
    **ALARM_FIRING,
    "NewStateValue": "OK",
    "OldStateValue": "ALARM",
    "NewStateReason": "Threshold Crossed: 5 datapoints were not greater than or equal to the threshold (1.0).",
}

# 서버 생성·교체 직후: 지표가 잠깐 없다가 돌아온다. 아무 일도 아니므로 보내지 않는다
INSUFFICIENT_TO_OK = {**ALARM_RECOVERED, "OldStateValue": "INSUFFICIENT_DATA"}
TO_INSUFFICIENT = {**ALARM_FIRING, "NewStateValue": "INSUFFICIENT_DATA", "OldStateValue": "OK"}

B2B_MEMORY = {
    "AlarmName": "peakpic-b2b-yuja-host-memory-high",
    "AlarmDescription": "B2B yuja: 서버 메모리 최대가 3분 연속 80% 를 넘었다.",
    "NewStateValue": "ALARM",
    "OldStateValue": "OK",
    "NewStateReason": "Threshold Crossed: 3 datapoints [88.4 (09/11/26 05:31:00), 86.1 (09/11/26 05:30:00), 83.7 (09/11/26 05:29:00)] were greater than the threshold (80.0).",
    "StateChangeTime": "2026-11-09T05:31:40.000+0000",
    "Trigger": {
        "MetricName": "mem_used_percent",
        "Namespace": "CWAgent",
        "Statistic": "MAXIMUM",
        "Period": 60,
        "EvaluationPeriods": 3,
        "ComparisonOperator": "GreaterThanThreshold",
        "Threshold": 80.0,
        "Dimensions": [{"name": "RuntimeId", "value": "abc123def456"}],
    },
}

RDS_STORAGE = {
    "AlarmName": "peakpic-rds-low-storage",
    "NewStateValue": "ALARM",
    "OldStateValue": "OK",
    "NewStateReason": "Threshold Crossed: 3 datapoints [3.9E9 (09/11/26 05:31:00), 4.1E9 (09/11/26 05:26:00), 4.2E9 (09/11/26 05:21:00)] were less than the threshold (4.294967296E9).",
    "StateChangeTime": "2026-11-09T05:31:40.000+0000",
    "Trigger": {
        "MetricName": "FreeStorageSpace",
        "Namespace": "AWS/RDS",
        "Statistic": "MINIMUM",
        "Period": 300,
        "EvaluationPeriods": 3,
        "ComparisonOperator": "LessThanThreshold",
        "Threshold": 4294967296.0,
    },
}

# 복합 알람(#231): Trigger 대신 AlarmRule·TriggeringChildren 이 온다
COMPOSITE = {
    "AlarmName": "peakpic-ec2-cpu-credit-exhausting",
    "AlarmDescription": "CPU 크레딧이 15 미만이면서 CPU 를 15% 위로 계속 쓰고 있다.",
    "AlarmRule": 'ALARM("peakpic-ec2-cpu-credit-low") AND ALARM("peakpic-ec2-cpu-busy")',
    "NewStateValue": "ALARM",
    "OldStateValue": "OK",
    "NewStateReason": "arn:aws:cloudwatch:ap-northeast-2:000000000000:alarm:peakpic-ec2-cpu-busy transitioned to ALARM at Sunday 09 November, 2026 05:31:40 UTC",
    "StateChangeTime": "2026-11-09T05:31:40.000+0000",
    "TriggeringChildren": [
        {"Arn": "arn:aws:cloudwatch:ap-northeast-2:000000000000:alarm:peakpic-ec2-cpu-busy", "State": {"Value": "ALARM"}},
    ],
}

DEPLOY_SUCCEEDED = {
    "source": "aws.autoscaling",
    "detail-type": "EC2 Auto Scaling Instance Refresh Succeeded",
    "time": "2026-08-22T07:00:00Z",
    "detail": {"AutoScalingGroupName": "peakpic-asg", "InstanceRefreshId": "b0f1-refresh-001"},
}

DEPLOY_FAILED = {**DEPLOY_SUCCEEDED, "detail-type": "EC2 Auto Scaling Instance Refresh Failed"}

PLAIN_TEXT = {"raw": "수동 발행 테스트 메시지"}

# (이름, 메시지, 보내야 하는가, 제목에 들어 있어야 하는 글자, 어떤 칸이 있어야 하는가)
CASES = [
    ("알람 발생 (B2C)", ALARM_FIRING, True, "🚨 서버 응답 없음 · 앱 (B2C)", ["현재 실패 서버", "기준", "지속"]),
    ("알람 해제 (ALARM→OK)", ALARM_RECOVERED, True, "✅ 해제 · 서버 응답 없음 · 앱 (B2C)", []),
    ("데이터 부족→정상 (건너뜀)", INSUFFICIENT_TO_OK, False, "", []),
    ("→데이터 부족 (건너뜀)", TO_INSUFFICIENT, False, "", []),
    ("알람 발생 (B2B 축제, %)", B2B_MEMORY, True, "🚨 서버 메모리 부족 · 축제 · yuja", ["현재 메모리", "기준", "지속"]),
    ("알람 발생 (바이트 → GiB)", RDS_STORAGE, True, "🚨 DB 저장 공간 부족 · 앱 (B2C)", ["현재 DB 여유 공간"]),
    ("복합 알람 (AlarmRule)", COMPOSITE, True, "🚨 CPU 크레딧 소진 임박 · 앱 (B2C)", ["조건"]),
    ("배포 반영 완료", DEPLOY_SUCCEEDED, True, "✅ 배포 반영 완료 · 앱 (B2C)", []),
    ("배포 반영 실패", DEPLOY_FAILED, True, "❌ 배포 반영 실패 · 앱 (B2C)", []),
    ("분류 실패 폴백", PLAIN_TEXT, True, "❔ 분류되지 않은 알림", []),
]

EXPECTED_VALUES = {
    "알람 발생 (B2C)": {"현재 실패 서버": "1대", "기준": "1대 이상", "지속": "5분 연속"},
    "알람 발생 (B2B 축제, %)": {"현재 메모리": "88%", "기준": "80% 초과", "지속": "3분 연속"},
    "알람 발생 (바이트 → GiB)": {"현재 DB 여유 공간": "3.6GiB", "기준": "4.0GiB 미만", "지속": "15분 연속"},
    "복합 알람 (AlarmRule)": {"조건": "「CPU 크레딧 낮음」 그리고 「CPU 바쁨」"},
}


def preview(embed):
    """Discord 가 그릴 모양을 터미널 글자로 흉내 낸다. <t:…> 토큰은 KST 로 바꿔 보여준다."""
    def plain(text):
        text = re.sub(r"<t:(\d+):f>", lambda m: _kst(_unix_to_iso(int(m.group(1)))), str(text or ""))
        return re.sub(r"<t:\d+:R>", "n분 전", text)

    lines = [f"┃ {plain(embed.get('title'))}"]
    for line in plain(embed.get("description")).splitlines():
        lines.append(f"┃ {line}")
    fields = embed.get("fields") or []
    if fields:
        lines.append("┃")
        lines.append("┃ " + "   ".join(f"{f['name']}: {plain(f['value'])}" for f in fields))
    if embed.get("footer"):
        lines.append(f"┃ ⋯ {embed['footer']['text']}")
    return "\n".join(lines)


def _unix_to_iso(unix):
    from datetime import datetime, timezone

    return datetime.fromtimestamp(unix, tz=timezone.utc).isoformat()


def main():
    post, as_json = "--post" in sys.argv, "--json" in sys.argv
    hook = os.environ.get("DISCORD_WEBHOOK_URL", "")
    if post and not hook:
        sys.exit("--post 를 쓰려면 DISCORD_WEBHOOK_URL 환경변수가 필요하다")

    for label, message, expect_send, expect_title, expect_fields in CASES:
        send, reason = should_notify(message)
        assert send == expect_send, f"{label}: 보내기 판단이 {send} (기대 {expect_send})"
        print(f"\n=== {label} ===")
        if not send:
            print(f"(건너뜀) {reason}")
            continue

        embed = build_embed(message)
        assert embed.get("title") == expect_title, f"{label}: 제목 '{embed.get('title')}' (기대 '{expect_title}')"
        assert isinstance(embed.get("color"), int), f"{label}: color 누락"
        names = [f["name"] for f in embed.get("fields") or []]
        for wanted in expect_fields:
            assert wanted in names, f"{label}: '{wanted}' 칸 없음 — {names}"
        values = {f["name"]: f["value"] for f in embed.get("fields") or []}
        for key, expected in EXPECTED_VALUES.get(label, {}).items():
            assert values.get(key) == expected, f"{label}: {key} = '{values.get(key)}' (기대 '{expected}')"

        print(json.dumps(embed, ensure_ascii=False, indent=2) if as_json else preview(embed))
        if post:
            embed["footer"] = {"text": f"시험 전송 · {label} · " + (embed.get("footer") or {}).get("text", "")}
            print(f"→ Discord 발송 HTTP {_post(hook, embed)}")

    print(f"\n전체 {len(CASES)}개 케이스 통과")


if __name__ == "__main__":
    main()
