# 알림 (CloudWatch → SNS → Discord)

관측성 3단계. `#80` 지표 노출, `#82` 수집·시각화에 이어 **이상을 사람에게 밀어주는(push)** 계층이다.

대시보드는 사람이 열어봐야만 보인다. 새벽에 인스턴스가 죽어도 다음에 열어볼 때까지 모른다.
장애를 사람이 발견하는 구조에서 **장애가 사람을 찾아오는 구조**로 바꾸는 것이 목적이다.

## 구성

```
CloudWatch 알람 7종 ─┐
                     ├─▶ SNS(peakpic-alarms) ─▶ Lambda(peakpic-alert-notify) ─▶ Discord
EventBridge(배포 결과)┘
```

| 파일 | 역할 |
|---|---|
| `setup-pipeline.sh` | SNS 토픽·Lambda·구독·EventBridge 규칙 생성 (멱등) |
| `create-alarms.sh` | CloudWatch 알람 7종 생성 (멱등, 대상 리소스 자동 탐색) |
| `../../lambda/alert-notify/` | 메시지 변환 Lambda 코드 |

### 기존 배포 알림(`deploy.yml`)과의 관계 — 중복이 아니라 짝

`deploy.yml`은 GitHub Actions 잡이 끝날 때 "🚀 롤링 교체 시작"을 알린다.
그 시점에 확정된 것은 **빌드·ECR push·`start-instance-refresh` 호출 성공**까지다.

```
T+0     🚀 롤링 교체 시작      ← deploy.yml (기존)
T+1~5분  ✅/❌ 롤링 교체 결과   ← EventBridge (이번 작업)
```

그 사이 구간은 지금까지 무보고였다. 새 인스턴스가 헬스체크에 실패하거나 용량이 부족해
교체가 멈춰도 Actions는 이미 초록으로 끝나 있어, **"배포 알림은 왔는데 옛 버전이 도는"** 상황을 알 수 없다.
`MinHealthyPercentage=100`(교체 전 여유 인스턴스를 먼저 띄우는 방식)이라 이 실패 가능성이 실재한다.

완료 알림은 "언제부터 검증해도 되는가"에 답하는 역할도 한다 — 시작 알림으로는 알 수 없는 정보다.

### 왜 Grafana Alerting이 아니라 CloudWatch인가

모니터링 EC2가 죽으면 거기 얹은 알람도 함께 죽는다 — **감시자는 자기 죽음을 알릴 수 없다.**
CloudWatch는 AWS 관리형이라 우리 인프라와 장애 도메인이 분리되어 이 역할에 맞다.
앱 지표(`ws_*`) 기반 알람은 성격이 달라 후속에서 Grafana로 검토한다.

## 알람 7종과 임계값 근거

임계값의 원칙은 **"위험한 숫자"가 아니라 "정상 운영에서 절대 밟지 않는 숫자"** 다.
정상 상태와 겹치는 임계값은 오탐을 낳고, 오탐이 반복되면 팀이 알람을 무시한다.

### A그룹 — 사실 판정형 (베이스라인 불필요)

| 알람 | 조건 | 근거 |
|---|---|---|
| `alb-unhealthy-host` | UnHealthyHostCount ≥ 1, 2회 × 60s | 헬스체크 탈락은 이분법적 사실. 2회 연속으로 순간 스파이크 배제 |
| `ec2-status-check-failed` | StatusCheckFailed ≥ 1, 2회 × 60s | 인스턴스/호스트 수준 장애 |
| `redis-connections-lost` | CurrConnections < 1, 300s | 앱-Redis 단절 = 시그널링 전면 마비. 지표 결측도 이상 신호이므로 `breaching` 처리 |
| `rds-low-storage` | FreeStorageSpace < 4GiB, 3회 × 300s | 할당 20GiB의 20%. 오토스케일링(최대 50GiB)이 자동 구제책이므로 "곧 작동하거나 실패했다"는 통지 역할 |
| `ec2-cpu-credit-low` | CPUCreditBalance < 15, 3회 × 300s | 아래 별도 설명 |

### B그룹 — 보수적 초기값 (출시 후 재조정)

| 알람 | 조건 | 재조정 계획 |
|---|---|---|
| `alb-5xx-spike` | 5XX > 5건 / 300s | 출시 전 트래픽이 0에 가까워 소량도 유의미. 실트래픽 확보 후 비율(5XX/전체) 기준 전환 검토. 무트래픽 구간은 `notBreaching` |
| `redis-memory-high` | 메모리 사용률 > 75%, 2회 × 300s | 세션은 TTL로 정리되어 증가가 유계. 실사용 추세 확인 후 조정 |

### CPU 크레딧 임계값을 15로 잡은 이유

t3는 버스트형이다. 평소 CPU를 적게 쓰면 크레딧이 쌓이고(t3.micro 시간당 12개, 최대 288),
필요할 때 태워서 순간 성능을 낸다. **0이 되면 기준선(10%) 성능으로 강등**되어 앱이 갑자기 느려진다.

관측된 잔고 42는 크레딧을 태워서 줄어든 값이 아니라 **배포로 인스턴스가 교체되어 아직 못 쌓은 값**이었다.
따라서 임계값을 40 근처로 잡으면 **배포할 때마다 오탐**이 난다.

```
0 ───── 15 ───────── 42 ──────────── 288
성능강등  알람        배포 직후 시작점   최대 누적
```

15는 배포 직후 상태와 겹치지 않으면서 0에 닿기 한참 전에 알린다. 여기에 15분 연속 조건으로 스파이크를 배제한다.

### 차원(dimension) 설계 주의

EC2 알람은 `InstanceId`가 아니라 **`AutoScalingGroupName` 차원**으로 건다.
인스턴스 ID로 걸면 배포(Instance Refresh)로 교체될 때마다 알람이 `INSUFFICIENT_DATA`가 되어 죽는다.
ASG 차원 + `Minimum` 통계면 "그룹 안 어느 인스턴스든 위험하면" 판정이 된다.

## 설치

관리 권한이 있는 환경에서 실행한다(콘솔 CloudShell 권장 — 배포용 `cli-deploy`에는 조회 권한이 없다).

```bash
# 1. 전달 파이프라인 (SNS → Lambda → Discord + 배포 이벤트 규칙)
export DISCORD_WEBHOOK_URL='https://discord.com/api/webhooks/...'
./setup-pipeline.sh

# 2. 알람 7종
./create-alarms.sh
```

## 검증

```bash
# 파이프라인 종단 확인 — SNS에 직접 발행
aws sns publish --region ap-northeast-2 \
  --topic-arn arn:aws:sns:ap-northeast-2:<계정ID>:peakpic-alarms \
  --subject '테스트' --message '파이프라인 확인'

# 알람 강제 발화 — 실제 장애 없이 알람→Discord 경로 확인
aws cloudwatch set-alarm-state --region ap-northeast-2 \
  --alarm-name peakpic-alb-unhealthy-host --state-value ALARM \
  --state-reason "종단 테스트"

# 복구 알림까지 확인
aws cloudwatch set-alarm-state --region ap-northeast-2 \
  --alarm-name peakpic-alb-unhealthy-host --state-value OK --state-reason "테스트 종료"

# 전체 알람 상태
aws cloudwatch describe-alarms --region ap-northeast-2 --alarm-name-prefix peakpic- \
  --query 'MetricAlarms[].{name:AlarmName,state:StateValue}' --output table
```

강제 발화 후 잠시 뒤 실제 지표로 재평가되므로 상태는 자동 정상화된다.

## 주의

- Discord 웹훅 URL은 Lambda 환경변수로만 주입한다. 레포·스크립트에 하드코딩 금지.
- 알람 추가 시 `create-alarms.sh`에 함께 기록한다 (콘솔에서만 만들면 재구축 불가).
- 출시 1~2주 후 B그룹 임계값 재조정 + 오탐/미탐 회고 예정.
