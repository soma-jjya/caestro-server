#!/usr/bin/env bash
# CloudWatch 알람 7종 생성. put-metric-alarm은 덮어쓰기라 여러 번 실행해도 안전하다.
# 임계값 근거는 README.md 참고. 대상 리소스는 자동 탐색하며 환경변수로 덮어쓸 수 있다.
#
#   ./create-alarms.sh

set -euo pipefail

REGION="${REGION:-ap-northeast-2}"
TOPIC_NAME="${TOPIC_NAME:-peakpic-alarms}"
ASG_NAME="${ASG_NAME:-peakpic-asg}"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
TOPIC_ARN="arn:aws:sns:${REGION}:${ACCOUNT_ID}:${TOPIC_NAME}"

# ALB/대상그룹 지표는 ARN 접미사를 차원 값으로 쓴다 (이름이 아님)
LB_ARN="${LB_ARN:-$(aws elbv2 describe-load-balancers --region "$REGION" \
  --query "LoadBalancers[?contains(LoadBalancerName, 'peakpic')].LoadBalancerArn | [0]" --output text)}"
TG_ARN="${TG_ARN:-$(aws elbv2 describe-target-groups --region "$REGION" \
  --query "TargetGroups[?contains(TargetGroupName, 'peakpic')].TargetGroupArn | [0]" --output text)}"
RDS_ID="${RDS_ID:-$(aws rds describe-db-instances --region "$REGION" \
  --query "DBInstances[0].DBInstanceIdentifier" --output text)}"
CACHE_ID="${CACHE_ID:-$(aws elasticache describe-cache-clusters --region "$REGION" \
  --query "CacheClusters[0].CacheClusterId" --output text)}"

for var in LB_ARN TG_ARN RDS_ID CACHE_ID; do
  value="${!var}"
  if [ -z "$value" ] || [ "$value" = "None" ]; then
    echo "✗ ${var} 자동 탐색 실패 — 환경변수로 직접 지정할 것"
    exit 1
  fi
done

LB_DIM="app/${LB_ARN##*:loadbalancer/app/}"
TG_DIM="targetgroup/${TG_ARN##*:targetgroup/}"
ALB_DIMS="Name=LoadBalancer,Value=${LB_DIM} Name=TargetGroup,Value=${TG_DIM}"  # 인자 2개로 분리되어야 하므로 사용처에서 따옴표 없이 전개

echo "대상: ASG=${ASG_NAME} RDS=${RDS_ID} Redis=${CACHE_ID}"
echo "      ALB=${LB_DIM}"
echo

alarm() {
  local name=$1 desc=$2
  shift 2
  aws cloudwatch put-metric-alarm --region "$REGION" \
    --alarm-name "$name" --alarm-description "$desc" \
    --alarm-actions "$TOPIC_ARN" --ok-actions "$TOPIC_ARN" "$@"
  echo "  ✓ ${name}"
}

echo "▶ A그룹 — 사실 판정형 (베이스라인 불필요)"

# 5분 연속: 배포(롤링 교체)로 인스턴스가 하나씩 빠지는 정상 구간(수 분)과 겹치지 않게 한다.
# 2분이면 배포마다 오탐 → 실제 첫 배포에서 발생 확인 후 상향(#85 후속 조정).
alarm "peakpic-alb-unhealthy-host" "ALB 대상 그룹에서 헬스체크에 실패한 인스턴스가 있다. 사용자 요청이 이미 실패 중일 수 있다." \
  --namespace AWS/ApplicationELB --metric-name UnHealthyHostCount \
  --statistic Maximum --period 60 --evaluation-periods 5 \
  --comparison-operator GreaterThanOrEqualToThreshold --threshold 1 \
  --dimensions $ALB_DIMS --treat-missing-data missing

# 3분 연속: 배포 중 새 인스턴스의 초기 상태 검사 실패가 잠깐 잡히는 것을 배제한다.
alarm "peakpic-ec2-status-check-failed" "EC2 인스턴스/호스트 상태 검사 실패. 인스턴스 자체에 문제가 있다." \
  --namespace AWS/EC2 --metric-name StatusCheckFailed \
  --statistic Maximum --period 60 --evaluation-periods 3 \
  --comparison-operator GreaterThanOrEqualToThreshold --threshold 1 \
  --dimensions "Name=AutoScalingGroupName,Value=${ASG_NAME}" --treat-missing-data missing

# 연결이 0이면 시그널링 전면 마비. 지표 자체가 사라지는 것도 이상 신호이므로 결측을 위반으로 본다
alarm "peakpic-redis-connections-lost" "앱과 Redis 사이 연결이 끊겼다. 세션·시그널링이 전면 마비된다." \
  --namespace AWS/ElastiCache --metric-name CurrConnections \
  --statistic Minimum --period 300 --evaluation-periods 1 \
  --comparison-operator LessThanThreshold --threshold 1 \
  --dimensions "Name=CacheClusterId,Value=${CACHE_ID}" --treat-missing-data breaching

# 4 GiB = 할당 20 GiB의 20%. 스토리지 오토스케일링(최대 50 GiB)이 곧 작동하거나 실패했다는 통지
alarm "peakpic-rds-low-storage" "RDS 여유 공간이 4GiB 미만. 오토스케일링이 작동할 시점이거나 실패했다." \
  --namespace AWS/RDS --metric-name FreeStorageSpace \
  --statistic Minimum --period 300 --evaluation-periods 3 \
  --comparison-operator LessThanThreshold --threshold 4294967296 \
  --dimensions "Name=DBInstanceIdentifier,Value=${RDS_ID}" --treat-missing-data missing

# 배포 직후 신규 인스턴스 잔고(관측값 42)보다 충분히 낮게 → 배포마다 울리는 오탐 방지
alarm "peakpic-ec2-cpu-credit-low" "버스트 크레딧이 소진되어 간다. 0이 되면 성능이 기준선으로 강등된다." \
  --namespace AWS/EC2 --metric-name CPUCreditBalance \
  --statistic Minimum --period 300 --evaluation-periods 3 \
  --comparison-operator LessThanThreshold --threshold 15 \
  --dimensions "Name=AutoScalingGroupName,Value=${ASG_NAME}" --treat-missing-data missing

echo "▶ B그룹 — 보수적 초기값 (출시 후 재조정)"

# 트래픽이 없으면 데이터포인트도 없다 → 결측을 정상으로 처리
alarm "peakpic-alb-5xx-spike" "서버 5XX 오류가 급증했다. 앱 예외 또는 백엔드 장애를 의심한다." \
  --namespace AWS/ApplicationELB --metric-name HTTPCode_Target_5XX_Count \
  --statistic Sum --period 300 --evaluation-periods 1 \
  --comparison-operator GreaterThanThreshold --threshold 5 \
  --dimensions $ALB_DIMS --treat-missing-data notBreaching

alarm "peakpic-redis-memory-high" "Redis 메모리 사용률이 높다. 한계에 도달하면 세션이 축출되어 유실된다." \
  --namespace AWS/ElastiCache --metric-name DatabaseMemoryUsagePercentage \
  --statistic Maximum --period 300 --evaluation-periods 2 \
  --comparison-operator GreaterThanThreshold --threshold 75 \
  --dimensions "Name=CacheClusterId,Value=${CACHE_ID}" --treat-missing-data missing

echo
echo "완료. 상태 확인:"
echo "  aws cloudwatch describe-alarms --region ${REGION} --alarm-name-prefix peakpic- \\"
echo "    --query 'MetricAlarms[].{name:AlarmName,state:StateValue}' --output table"
