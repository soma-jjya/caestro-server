#!/usr/bin/env bash
# 알림 전달 파이프라인 구축: SNS 토픽 → 변환 Lambda → Discord, + 배포 이벤트 규칙.
# 여러 번 실행해도 안전하다(모든 단계 멱등). CloudShell 등 관리 권한이 있는 환경에서 실행.
#
#   DISCORD_WEBHOOK_URL='https://discord.com/api/webhooks/...' ./setup-pipeline.sh

set -euo pipefail

REGION="${REGION:-ap-northeast-2}"
TOPIC_NAME="${TOPIC_NAME:-peakpic-alarms}"
FUNCTION_NAME="${FUNCTION_NAME:-peakpic-alert-notify}"
ROLE_NAME="${ROLE_NAME:-peakpic-alert-notify-role}"
RULE_NAME="${RULE_NAME:-peakpic-deploy-notify}"
SOURCE_DIR="${SOURCE_DIR:-$(cd "$(dirname "$0")/../../lambda/alert-notify" && pwd)}"

: "${DISCORD_WEBHOOK_URL:?DISCORD_WEBHOOK_URL 환경변수가 필요하다}"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
TOPIC_ARN="arn:aws:sns:${REGION}:${ACCOUNT_ID}:${TOPIC_NAME}"
FUNCTION_ARN="arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${FUNCTION_NAME}"

echo "▶ 1/5 SNS 토픽"
aws sns create-topic --name "$TOPIC_NAME" --region "$REGION" >/dev/null
# CloudWatch 알람과 EventBridge가 이 토픽에 발행할 수 있도록 허용 (계정 소유자 권한은 유지)
aws sns set-topic-attributes --region "$REGION" --topic-arn "$TOPIC_ARN" \
  --attribute-name Policy --attribute-value "$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowAccountOwner",
      "Effect": "Allow",
      "Principal": {"AWS": "*"},
      "Action": ["SNS:Publish","SNS:Subscribe","SNS:GetTopicAttributes","SNS:SetTopicAttributes","SNS:ListSubscriptionsByTopic","SNS:DeleteTopic","SNS:AddPermission","SNS:RemovePermission","SNS:Receive"],
      "Resource": "${TOPIC_ARN}",
      "Condition": {"StringEquals": {"AWS:SourceOwner": "${ACCOUNT_ID}"}}
    },
    {
      "Sid": "AllowServicePublish",
      "Effect": "Allow",
      "Principal": {"Service": ["events.amazonaws.com", "cloudwatch.amazonaws.com"]},
      "Action": "SNS:Publish",
      "Resource": "${TOPIC_ARN}"
    }
  ]
}
JSON
)"
echo "  ✓ ${TOPIC_ARN}"

echo "▶ 2/5 Lambda 실행 역할"
if ! aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  aws iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]
  }' >/dev/null
  aws iam attach-role-policy --role-name "$ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
  echo "  ✓ 생성 — IAM 전파 대기 10초"
  sleep 10
else
  echo "  ✓ 기존 역할 사용"
fi
ROLE_ARN=$(aws iam get-role --role-name "$ROLE_NAME" --query Role.Arn --output text)

echo "▶ 3/5 Lambda 배포"
ZIP=$(mktemp -d)/function.zip
(cd "$SOURCE_DIR" && zip -q -r "$ZIP" handler.py format.py)
if aws lambda get-function --function-name "$FUNCTION_NAME" --region "$REGION" >/dev/null 2>&1; then
  aws lambda update-function-code --function-name "$FUNCTION_NAME" --zip-file "fileb://$ZIP" --region "$REGION" >/dev/null
  aws lambda wait function-updated --function-name "$FUNCTION_NAME" --region "$REGION"
  aws lambda update-function-configuration --function-name "$FUNCTION_NAME" --region "$REGION" \
    --environment "Variables={DISCORD_WEBHOOK_URL=$DISCORD_WEBHOOK_URL}" >/dev/null
  echo "  ✓ 코드·설정 갱신"
else
  aws lambda create-function --function-name "$FUNCTION_NAME" --region "$REGION" \
    --runtime python3.12 --handler handler.lambda_handler --role "$ROLE_ARN" \
    --timeout 20 --memory-size 128 --zip-file "fileb://$ZIP" \
    --environment "Variables={DISCORD_WEBHOOK_URL=$DISCORD_WEBHOOK_URL}" >/dev/null
  aws lambda wait function-active --function-name "$FUNCTION_NAME" --region "$REGION"
  echo "  ✓ 신규 생성"
fi

echo "▶ 4/5 SNS → Lambda 구독"
aws lambda add-permission --function-name "$FUNCTION_NAME" --region "$REGION" \
  --statement-id sns-invoke --action lambda:InvokeFunction \
  --principal sns.amazonaws.com --source-arn "$TOPIC_ARN" >/dev/null 2>&1 || echo "  (호출 권한 이미 존재)"
aws sns subscribe --region "$REGION" --topic-arn "$TOPIC_ARN" \
  --protocol lambda --notification-endpoint "$FUNCTION_ARN" >/dev/null
echo "  ✓ 구독 완료"

echo "▶ 5/5 EventBridge 배포 이벤트 규칙"
aws events put-rule --region "$REGION" --name "$RULE_NAME" \
  --description "ASG 인스턴스 새로고침(배포) 결과를 알림 토픽으로 전달" \
  --event-pattern '{
    "source": ["aws.autoscaling"],
    "detail-type": [
      "EC2 Auto Scaling Instance Refresh Succeeded",
      "EC2 Auto Scaling Instance Refresh Failed",
      "EC2 Auto Scaling Instance Refresh Cancelled"
    ]
  }' >/dev/null
aws events put-targets --region "$REGION" --rule "$RULE_NAME" \
  --targets "Id=sns,Arn=${TOPIC_ARN}" >/dev/null
echo "  ✓ ${RULE_NAME}"

echo
echo "완료. 종단 확인:"
echo "  aws sns publish --region ${REGION} --topic-arn ${TOPIC_ARN} --subject '테스트' --message '파이프라인 확인'"
