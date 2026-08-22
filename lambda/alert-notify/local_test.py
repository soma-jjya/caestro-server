"""로컬 검증: AWS 없이 임베드 변환 결과를 확인한다.

    python3 local_test.py            # 변환 결과만 출력
    python3 local_test.py --post     # DISCORD_WEBHOOK_URL로 실제 발송(테스트 채널 권장)
"""

import json
import os
import sys

from format import build_embed
from handler import _post

ALARM_FIRING = {
    "AlarmName": "peakpic-alb-unhealthy-host",
    "AlarmDescription": "ALB 대상 그룹에서 헬스체크에 실패한 인스턴스가 있다.",
    "NewStateValue": "ALARM",
    "NewStateReason": "Threshold Crossed: 2 datapoints [1.0, 1.0] were greater than or equal to the threshold (1.0).",
    "StateChangeTime": "2026-08-22T06:12:34.567+0000",
    "Region": "Asia Pacific (Seoul)",
    "Trigger": {
        "MetricName": "UnHealthyHostCount",
        "Namespace": "AWS/ApplicationELB",
        "Statistic": "MAXIMUM",
        "Period": 60,
        "EvaluationPeriods": 2,
        "ComparisonOperator": "GreaterThanOrEqualToThreshold",
        "Threshold": 1.0,
        "Dimensions": [{"name": "TargetGroup", "value": "targetgroup/peakpic-tg/abc123"}],
    },
}

ALARM_RECOVERED = {
    **ALARM_FIRING,
    "NewStateValue": "OK",
    "NewStateReason": "Threshold Crossed: 2 datapoints were not greater than or equal to the threshold (1.0).",
}

DEPLOY_SUCCEEDED = {
    "source": "aws.autoscaling",
    "detail-type": "EC2 Auto Scaling Instance Refresh Succeeded",
    "time": "2026-08-22T07:00:00Z",
    "detail": {"AutoScalingGroupName": "peakpic-asg", "InstanceRefreshId": "b0f1-refresh-001"},
}

DEPLOY_FAILED = {
    **DEPLOY_SUCCEEDED,
    "detail-type": "EC2 Auto Scaling Instance Refresh Failed",
}

PLAIN_TEXT = {"raw": "수동 발행 테스트 메시지"}

CASES = [
    ("알람 발생", ALARM_FIRING),
    ("알람 해제", ALARM_RECOVERED),
    ("롤링 교체 완료", DEPLOY_SUCCEEDED),
    ("롤링 교체 실패", DEPLOY_FAILED),
    ("분류 실패 폴백", PLAIN_TEXT),
]


def main():
    post = "--post" in sys.argv
    hook = os.environ.get("DISCORD_WEBHOOK_URL", "")
    if post and not hook:
        sys.exit("--post 를 쓰려면 DISCORD_WEBHOOK_URL 환경변수가 필요하다")

    for label, message in CASES:
        embed = build_embed(message)
        print(f"\n=== {label} ===")
        print(json.dumps(embed, ensure_ascii=False, indent=2))
        assert embed.get("title"), f"{label}: title 누락"
        assert isinstance(embed.get("color"), int), f"{label}: color 누락"
        if post:
            print(f"→ Discord 발송 HTTP {_post(hook, embed)}")

    print("\n전체 케이스 변환 성공")


if __name__ == "__main__":
    main()
