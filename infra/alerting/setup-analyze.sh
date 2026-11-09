#!/usr/bin/env bash
# 알람 분석 Lambda(#230) 설치: 읽기 전용 역할 → 패키지(anthropic SDK 포함) → 함수 → SNS 구독(ALARM 전이만).
# 여러 번 실행해도 안전하다. 관리 권한 환경(CloudShell)에서 실행. setup-pipeline.sh 로 토픽이 먼저 있어야 한다.
#
#   LOG_GROUP='<앱 로그 그룹>' DISCORD_WEBHOOK_URL='https://discord.com/api/webhooks/...' ./setup-analyze.sh
#
# 전제: Secrets Manager 에 모델 API 키 시크릿이 있어야 한다(기본 이름 peakpic/anthropic-api-key, 평문 또는 {"ANTHROPIC_API_KEY": "..."}).
#   aws secretsmanager create-secret --name peakpic/anthropic-api-key --secret-string 'sk-ant-...'

set -euo pipefail

REGION="${REGION:-ap-northeast-2}"
TOPIC_NAME="${TOPIC_NAME:-peakpic-alarms}"
FUNCTION_NAME="${FUNCTION_NAME:-peakpic-alert-analyze}"
ROLE_NAME="${ROLE_NAME:-peakpic-alert-analyze-role}"
POLICY_NAME="${POLICY_NAME:-peakpic-alert-analyze-readonly}"
ASG_NAME="${ASG_NAME:-peakpic-asg}"
GITHUB_REPO="${GITHUB_REPO:-soma-jjya/caestro-server}"
ANTHROPIC_SECRET_ID="${ANTHROPIC_SECRET_ID:-peakpic/anthropic-api-key}"
MODEL="${MODEL:-claude-opus-5-5}"
SOURCE_DIR="${SOURCE_DIR:-$(cd "$(dirname "$0")/../../lambda/alert-analyze" && pwd)}"

: "${LOG_GROUP:?LOG_GROUP 환경변수가 필요하다 (앱 로그가 들어가는 CloudWatch Logs 그룹)}"
: "${DISCORD_WEBHOOK_URL:?DISCORD_WEBHOOK_URL 환경변수가 필요하다}"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
TOPIC_ARN="arn:aws:sns:${REGION}:${ACCOUNT_ID}:${TOPIC_NAME}"
FUNCTION_ARN="arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${FUNCTION_NAME}"

echo "▶ 0/5 대상 리소스 탐색 (create-alarms.sh 와 같은 규칙)"
LB_ARN="${LB_ARN:-$(aws elbv2 describe-load-balancers --region "$REGION" \
  --query "LoadBalancers[?contains(LoadBalancerName, 'peakpic')].LoadBalancerArn | [0]" --output text)}"
TG_ARN="${TG_ARN:-$(aws elbv2 describe-target-groups --region "$REGION" \
  --query "TargetGroups[?contains(TargetGroupName, 'peakpic')].TargetGroupArn | [0]" --output text)}"
RDS_ID="${RDS_ID:-$(aws rds describe-db-instances --region "$REGION" --query "DBInstances[0].DBInstanceIdentifier" --output text)}"
CACHE_ID="${CACHE_ID:-$(aws elasticache describe-cache-clusters --region "$REGION" --query "CacheClusters[0].CacheClusterId" --output text)}"
for var in LB_ARN TG_ARN RDS_ID CACHE_ID; do
  value="${!var}"
  if [ -z "$value" ] || [ "$value" = "None" ]; then echo "✗ ${var} 자동 탐색 실패 — 환경변수로 지정할 것"; exit 1; fi
done
LB_DIM="app/${LB_ARN##*:loadbalancer/app/}"
TG_DIM="targetgroup/${TG_ARN##*:targetgroup/}"
# ALB 액세스 로그 위치는 ALB 속성에서 읽는다(#138 에서 켠 버킷)
ATTRS=$(aws elbv2 describe-load-balancer-attributes --region "$REGION" --load-balancer-arn "$LB_ARN" --query "Attributes" --output json)
ALB_LOG_BUCKET="${ALB_LOG_BUCKET:-$(echo "$ATTRS" | python3 -c 'import json,sys; a={x["Key"]:x["Value"] for x in json.load(sys.stdin)}; print(a.get("access_logs.s3.bucket",""))')}"
ALB_LOG_PREFIX="${ALB_LOG_PREFIX:-$(echo "$ATTRS" | python3 -c 'import json,sys; a={x["Key"]:x["Value"] for x in json.load(sys.stdin)}; print(a.get("access_logs.s3.prefix",""))')}"
[ -n "$ALB_LOG_BUCKET" ] || { echo "✗ ALB 액세스 로그가 꺼져 있다(#138 참고). ALB_LOG_BUCKET 을 지정하거나 먼저 켤 것"; exit 1; }
aws secretsmanager describe-secret --region "$REGION" --secret-id "$ANTHROPIC_SECRET_ID" >/dev/null \
  || { echo "✗ 시크릿 ${ANTHROPIC_SECRET_ID} 가 없다 — 머리말의 create-secret 참고"; exit 1; }
SECRET_ARN=$(aws secretsmanager describe-secret --region "$REGION" --secret-id "$ANTHROPIC_SECRET_ID" --query ARN --output text)
echo "  ALB=${LB_DIM} TG=${TG_DIM} ASG=${ASG_NAME} RDS=${RDS_ID} Redis=${CACHE_ID}"
echo "  로그 그룹=${LOG_GROUP} 액세스 로그=s3://${ALB_LOG_BUCKET}/${ALB_LOG_PREFIX}"

echo "▶ 1/5 실행 역할 + 읽기 전용 정책"
if ! aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  aws iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]
  }' >/dev/null
  aws iam attach-role-policy --role-name "$ROLE_NAME" --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
  echo "  ✓ 역할 생성 — IAM 전파 대기 10초"; sleep 10
fi
# 조회만. 쓰기 권한은 어디에도 없다(자동 조치 금지)
aws iam put-role-policy --role-name "$ROLE_NAME" --policy-name "$POLICY_NAME" --policy-document "$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {"Sid": "LogsInsights", "Effect": "Allow",
     "Action": ["logs:StartQuery", "logs:StopQuery", "logs:GetQueryResults", "logs:DescribeLogGroups"],
     "Resource": "*"},
    {"Sid": "Metrics", "Effect": "Allow", "Action": ["cloudwatch:GetMetricData"], "Resource": "*"},
    {"Sid": "AccessLogsList", "Effect": "Allow", "Action": ["s3:ListBucket"], "Resource": "arn:aws:s3:::${ALB_LOG_BUCKET}"},
    {"Sid": "AccessLogsRead", "Effect": "Allow", "Action": ["s3:GetObject"], "Resource": "arn:aws:s3:::${ALB_LOG_BUCKET}/*"},
    {"Sid": "Deploys", "Effect": "Allow",
     "Action": ["autoscaling:DescribeInstanceRefreshes", "autoscaling:DescribeScalingActivities"], "Resource": "*"},
    {"Sid": "ApiKey", "Effect": "Allow", "Action": ["secretsmanager:GetSecretValue"], "Resource": "${SECRET_ARN}"}
  ]
}
JSON
)"
ROLE_ARN=$(aws iam get-role --role-name "$ROLE_NAME" --query Role.Arn --output text)
echo "  ✓ ${ROLE_ARN}"

echo "▶ 2/5 패키지 (anthropic SDK 를 Lambda 플랫폼용 휠로 받아 묶는다)"
BUILD=$(mktemp -d)
python3 -m pip install -q -r "$SOURCE_DIR/requirements.txt" --target "$BUILD" \
  --platform manylinux2014_x86_64 --implementation cp --python-version 3.12 --only-binary=:all: --upgrade
cp "$SOURCE_DIR"/*.py "$BUILD"/
cp "$SOURCE_DIR"/../alert-notify/alarm_catalog.py "$BUILD"/   # 알람 이름표는 alert-notify 와 같은 파일을 쓴다 (#254)
rm -rf "$BUILD"/local_test.py "$BUILD"/fixtures.py "$BUILD"/*.dist-info/RECORD
ZIP="$BUILD.zip"
(cd "$BUILD" && zip -q -r "$ZIP" . -x '*/__pycache__/*')
echo "  ✓ $(du -h "$ZIP" | cut -f1)"

echo "▶ 3/5 Lambda"
ENV_VARS="Variables={DISCORD_WEBHOOK_URL=${DISCORD_WEBHOOK_URL},LOG_GROUP=${LOG_GROUP},ALB_LOG_BUCKET=${ALB_LOG_BUCKET},ALB_LOG_PREFIX=${ALB_LOG_PREFIX},ALB_DIM=${LB_DIM},TG_DIM=${TG_DIM},ASG_NAME=${ASG_NAME},CACHE_ID=${CACHE_ID},RDS_ID=${RDS_ID},GITHUB_REPO=${GITHUB_REPO},ANTHROPIC_SECRET_ID=${ANTHROPIC_SECRET_ID},MODEL=${MODEL}}"
if aws lambda get-function --function-name "$FUNCTION_NAME" --region "$REGION" >/dev/null 2>&1; then
  aws lambda update-function-code --function-name "$FUNCTION_NAME" --zip-file "fileb://$ZIP" --region "$REGION" >/dev/null
  aws lambda wait function-updated --function-name "$FUNCTION_NAME" --region "$REGION"
  aws lambda update-function-configuration --function-name "$FUNCTION_NAME" --region "$REGION" \
    --timeout 150 --memory-size 512 --environment "$ENV_VARS" >/dev/null
  echo "  ✓ 코드·설정 갱신"
else
  aws lambda create-function --function-name "$FUNCTION_NAME" --region "$REGION" \
    --runtime python3.12 --architectures x86_64 --handler handler.lambda_handler --role "$ROLE_ARN" \
    --timeout 150 --memory-size 512 --zip-file "fileb://$ZIP" --environment "$ENV_VARS" >/dev/null
  aws lambda wait function-active --function-name "$FUNCTION_NAME" --region "$REGION"
  echo "  ✓ 신규 생성"
fi
# 같은 알람으로 분석이 두 번 돌지 않게 비동기 재시도를 끈다(함수도 예외를 던지지 않는다)
aws lambda put-function-event-invoke-config --function-name "$FUNCTION_NAME" --region "$REGION" \
  --maximum-retry-attempts 0 --maximum-event-age-in-seconds 600 >/dev/null

echo "▶ 4/5 SNS 구독 (ALARM 전이만 — OK·배포 이벤트는 필터 정책에서 걸러진다)"
aws lambda add-permission --function-name "$FUNCTION_NAME" --region "$REGION" \
  --statement-id sns-invoke --action lambda:InvokeFunction \
  --principal sns.amazonaws.com --source-arn "$TOPIC_ARN" >/dev/null 2>&1 || echo "  (호출 권한 이미 존재)"
SUB_ARN=$(aws sns subscribe --region "$REGION" --topic-arn "$TOPIC_ARN" \
  --protocol lambda --notification-endpoint "$FUNCTION_ARN" --return-subscription-arn --query SubscriptionArn --output text)
aws sns set-subscription-attributes --region "$REGION" --subscription-arn "$SUB_ARN" \
  --attribute-name FilterPolicyScope --attribute-value MessageBody
aws sns set-subscription-attributes --region "$REGION" --subscription-arn "$SUB_ARN" \
  --attribute-name FilterPolicy --attribute-value '{"NewStateValue":["ALARM"]}'
echo "  ✓ ${SUB_ARN}"

echo "▶ 5/5 끝. 연기 테스트(실제 알람 없이 함수만 호출 — 과거 시각이라 데이터는 비어 있어도 흐름은 확인된다):"
echo "  aws lambda invoke --region ${REGION} --function-name ${FUNCTION_NAME} \\"
echo "    --cli-binary-format raw-in-base64-out --payload file://${SOURCE_DIR}/eval/sample-sns-event.json /dev/stdout"
rm -rf "$BUILD" "$ZIP"
