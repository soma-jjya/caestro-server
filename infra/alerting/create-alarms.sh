#!/usr/bin/env bash
# CloudWatch 알람 생성 (지표 알람 8종 + 복합 알람 1종 + 로그 지표 알람 1종). put-metric-alarm은 덮어쓰기라 여러 번 실행해도 안전하다.
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

# 알리지 않는 알람. 복합 알람의 재료로만 쓴다. put-metric-alarm 은 전체를 덮어쓰므로 기존 알림 동작도 함께 떨어진다.
alarm_silent() {
  local name=$1 desc=$2
  shift 2
  aws cloudwatch put-metric-alarm --region "$REGION" \
    --alarm-name "$name" --alarm-description "$desc" "$@"
  echo "  ✓ ${name} (알림 없음)"
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

# t3 는 런치 크레딧이 없어 새 인스턴스가 잔고 0 에서 시작한다(#231). 배포·증설 직후 15분은 그룹 Minimum 이 15 미만인 것이
# 구조적으로 항상 참이라, 잔고만 보면 배포마다 울린다. "잔고 낮음" 과 "CPU 를 기준선(10%) 위로 계속 쓰는 중" 이 둘 다 참일 때만
# 복합 알람으로 알린다. 새 인스턴스는 잔고가 낮아도 한가하므로 걸리지 않고, 진짜 소진은 둘 다 참이라 걸린다.
alarm_silent "peakpic-ec2-cpu-credit-low" "버스트 크레딧 잔고가 15 미만. 단독으로는 알리지 않고 cpu-busy 와 묶어 판단한다(#231)." \
  --namespace AWS/EC2 --metric-name CPUCreditBalance \
  --statistic Minimum --period 300 --evaluation-periods 3 \
  --comparison-operator LessThanThreshold --threshold 15 \
  --dimensions "Name=AutoScalingGroupName,Value=${ASG_NAME}" --treat-missing-data missing

alarm_silent "peakpic-ec2-cpu-busy" "CPU 평균이 15분째 15% 를 넘는다(t3 기준선 10% 위). 단독으로는 알리지 않는다(#231)." \
  --namespace AWS/EC2 --metric-name CPUUtilization \
  --statistic Average --period 300 --evaluation-periods 3 \
  --comparison-operator GreaterThanThreshold --threshold 15 \
  --dimensions "Name=AutoScalingGroupName,Value=${ASG_NAME}" --treat-missing-data missing

aws cloudwatch put-composite-alarm --region "$REGION" \
  --alarm-name "peakpic-ec2-cpu-credit-exhausting" \
  --alarm-description "CPU 크레딧이 15 미만이면서 CPU 를 15% 위로 계속 쓰고 있다. 0 이 되면 기준선 성능으로 강등되거나(standard) 추가 과금된다(unlimited)." \
  --alarm-rule 'ALARM("peakpic-ec2-cpu-credit-low") AND ALARM("peakpic-ec2-cpu-busy")' \
  --alarm-actions "$TOPIC_ARN" --ok-actions "$TOPIC_ARN"
echo "  ✓ peakpic-ec2-cpu-credit-exhausting (복합: credit-low AND cpu-busy)"

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

echo "▶ C그룹 — 앱 로그 기반 (LOG_GROUP 이 있을 때만)"

# 외부 API 서킷이 열리면 앱이 "Circuit opened: name=…" 한 줄을 남긴다(#271, ResilienceConfig). 지표 필터로 세어 알람을 건다.
# 열리는 동안 그 API(FCM 등)를 쓰는 알림이 버려지므로 1건이라도 알린다. 로그 그룹은 setup-analyze.sh 와 같은 LOG_GROUP 환경변수.
LOG_GROUP="${LOG_GROUP:-}"
if [ -n "$LOG_GROUP" ]; then
  aws logs put-metric-filter --region "$REGION" --log-group-name "$LOG_GROUP" \
    --filter-name "peakpic-circuit-opened" \
    --filter-pattern '{ $.message = "Circuit opened:*" }' \
    --metric-transformations metricName=CircuitOpened,metricNamespace=PeakPic/App,metricValue=1,defaultValue=0
  alarm "peakpic-app-circuit-open" "외부 API 서킷이 열렸다(앱 로그 'Circuit opened'). 열려 있는 동안 그 API 로 가는 호출(FCM 푸시 등)은 버려진다. 어떤 서킷인지는 로그의 name= 을 본다." \
    --namespace PeakPic/App --metric-name CircuitOpened \
    --statistic Sum --period 300 --evaluation-periods 1 \
    --comparison-operator GreaterThanOrEqualToThreshold --threshold 1 \
    --treat-missing-data notBreaching
else
  echo "  - peakpic-app-circuit-open 건너뜀: LOG_GROUP 이 비어 있다 (예: LOG_GROUP=/peakpic/app ./create-alarms.sh)"
fi

echo
# 크레딧 모드 확인(#231 전제). unlimited 면 잔고 0 에서 성능 강등 대신 과금이 난다. 어느 쪽이든 복합 알람은 "크레딧을 태우는 중" 신호로 유효하다.
INSTANCE_ID=$(aws autoscaling describe-auto-scaling-groups --region "$REGION" --auto-scaling-group-names "$ASG_NAME" \
  --query "AutoScalingGroups[0].Instances[0].InstanceId" --output text 2>/dev/null || true)
if [ -n "$INSTANCE_ID" ] && [ "$INSTANCE_ID" != "None" ]; then
  CREDIT_MODE=$(aws ec2 describe-instance-credit-specifications --region "$REGION" --instance-ids "$INSTANCE_ID" \
    --query "InstanceCreditSpecifications[0].CpuCredits" --output text 2>/dev/null || echo "?")
  echo "크레딧 모드: ${CREDIT_MODE} (${INSTANCE_ID})"
fi
echo
echo "완료. 상태 확인:"
echo "  aws cloudwatch describe-alarms --region ${REGION} --alarm-name-prefix peakpic- --alarm-types MetricAlarm CompositeAlarm \\"
echo "    --query '[MetricAlarms[].{name:AlarmName,state:StateValue,notify:length(AlarmActions)},CompositeAlarms[].{name:AlarmName,state:StateValue,notify:length(AlarmActions)}]' --output table"
