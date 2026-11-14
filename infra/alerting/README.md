# 알림 (CloudWatch → SNS → Discord)

관측성 3단계. `#80` 지표 노출, `#82` 수집·시각화에 이어 **이상을 사람에게 밀어주는(push)** 계층이다.

대시보드는 사람이 열어봐야만 보인다. 새벽에 인스턴스가 죽어도 다음에 열어볼 때까지 모른다.
장애를 사람이 발견하는 구조에서 **장애가 사람을 찾아오는 구조**로 바꾸는 것이 목적이다.

## 구성

```
CloudWatch 알람 9종 ─┐
                     ├─▶ SNS(peakpic-alarms) ─▶ Lambda(peakpic-alert-notify) ─▶ Discord
EventBridge(배포 결과)┘
```

| 파일 | 역할 |
|---|---|
| `setup-pipeline.sh` | SNS 토픽·Lambda·구독·EventBridge 규칙 생성 (멱등) |
| `create-alarms.sh` | CloudWatch 알람 10종 생성 (지표 8 + 복합 1 + 로그 지표 1, 멱등, 대상 리소스 자동 탐색. 로그 알람은 `LOG_GROUP` 환경변수가 있을 때만) |
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

## 알람 9종과 임계값 근거

임계값의 원칙은 **"위험한 숫자"가 아니라 "정상 운영에서 절대 밟지 않는 숫자"** 다.
정상 상태와 겹치는 임계값은 오탐을 낳고, 오탐이 반복되면 팀이 알람을 무시한다.

### A그룹 — 사실 판정형 (베이스라인 불필요)

| 알람 | 조건 | 근거 |
|---|---|---|
| `alb-unhealthy-host` | UnHealthyHostCount ≥ 1, 5회 × 60s | 헬스체크 탈락은 이분법적 사실. 5분 연속 = 배포(롤링 교체)로 인스턴스가 하나씩 빠지는 정상 구간과 겹치지 않음 (아래 오탐 조정 참고) |
| `ec2-status-check-failed` | StatusCheckFailed ≥ 1, 3회 × 60s | 인스턴스/호스트 수준 장애. 배포 중 새 인스턴스 초기 검사 실패 배제 위해 3분 |
| `redis-connections-lost` | CurrConnections < 1, 300s | 앱-Redis 단절 = 시그널링 전면 마비. 지표 결측도 이상 신호이므로 `breaching` 처리 |
| `rds-low-storage` | FreeStorageSpace < 4GiB, 3회 × 300s | 할당 20GiB의 20%. 오토스케일링(최대 50GiB)이 자동 구제책이므로 "곧 작동하거나 실패했다"는 통지 역할 |
| `ec2-cpu-credit-low` | CPUCreditBalance < 15, 3회 × 300s | **알리지 않음.** 아래 복합 알람의 재료 |
| `ec2-cpu-busy` | CPUUtilization 평균 > 15%, 3회 × 300s | **알리지 않음.** t3 기준선 10% 위로 계속 쓰는 중 |
| `ec2-cpu-credit-exhausting` | 복합: ALARM(credit-low) AND ALARM(cpu-busy) | 아래 별도 설명 |

### B그룹 — 보수적 초기값 (출시 후 재조정)

| 알람 | 조건 | 재조정 계획 |
|---|---|---|
| `alb-5xx-spike` | 5XX > 5건 / 300s | 출시 전 트래픽이 0에 가까워 소량도 유의미. 실트래픽 확보 후 비율(5XX/전체) 기준 전환 검토. 무트래픽 구간은 `notBreaching` |
| `redis-memory-high` | 메모리 사용률 > 75%, 2회 × 300s | 세션은 TTL로 정리되어 증가가 유계. 실사용 추세 확인 후 조정 |
| `app-circuit-open` | 앱 로그 `Circuit opened:` ≥ 1건 / 300s (지표 필터 `PeakPic/App CircuitOpened`) | 외부 API 서킷이 열리면 그 사이 호출(FCM 푸시)이 버려진다(#269 에서 이틀간 18건). 1건도 알린다. 로그 그룹을 모르면 만들지 않는다(`LOG_GROUP=/… ./create-alarms.sh`) |

### CPU 크레딧은 복합 알람으로 (#231)

t3는 버스트형이다. 평소 CPU를 적게 쓰면 크레딧이 쌓이고(t3.micro 시간당 12개, 최대 288),
필요할 때 태워서 순간 성능을 낸다. **0이 되면 기준선(10%) 성능으로 강등**되어(standard 모드) 앱이 갑자기 느려지고,
unlimited 모드면 강등 대신 **추가 과금**이 난다.

처음에는 잔고 하나로 알렸다(< 15, 15분). 그런데 **배포(롤링 교체)마다 울렸다.**
t3는 T2와 달리 런치 크레딧이 없어 새 인스턴스는 **잔고 0에서 시작**하고, 유휴 상태로도 15에 닿으려면 약 75분이 걸린다.
교체 직후 15분 동안 그룹 Minimum이 15 미만인 것은 구조적으로 항상 참이라, 임계값을 더 낮추거나 기간을 늘려도
배포 오탐은 조건 따라 남고 진짜 소진은 더 늦게 알게 된다. (초기 README의 "배포 직후 관측값 42"는 교체 한참 뒤에 읽은 값이었다.)

그래서 **"잔고가 낮다"와 "크레딧을 태우고 있다"가 둘 다 참일 때만** 알린다.

| 상황 | credit-low | cpu-busy | 알림 |
|---|---|---|---|
| 배포·증설 직후 새 인스턴스 | ALARM (잔고 0부터) | OK (한가함) | 없음 |
| 진짜 소진 (트래픽으로 계속 태움) | ALARM | ALARM | **발생** |

두 재료 알람에는 알림 동작이 없고, 복합 알람 `peakpic-ec2-cpu-credit-exhausting`에만 SNS가 걸린다.
축제 서버(B2B, `peakpic-b2b-server` `infra/festival/observability.tf`)도 같은 구조다.
`create-alarms.sh`가 끝에 현재 인스턴스의 크레딧 모드(standard/unlimited)를 출력한다. 어느 쪽이든 복합 알람은 "지금 크레딧을 태우는 중"이라는 신호로 유효하다.

### 오탐 조정 기록 — 배포 중 UnHealthyHost 알람 (2026-08-22)

첫 배포에서 `alb-unhealthy-host`가 발화했다. 원인은 장애가 아니라 **롤링 교체 그 자체**였다.
`MinHealthyPercentage=100`이라도 인스턴스가 하나씩 교체되는 수 분 동안 UnHealthyHostCount가
잠깐 1이 되고, 임계값이 2분 연속이라 그 구간에 걸렸다.

원칙("정상 운영에서 밟는 값이면 나쁜 임계값") 그대로의 사례다. 지속 시간을 **2분 → 5분**으로 늘려
배포 정상 구간과 겹치지 않게 했다. 개수 기준(≥1 → ≥2)은 쓰지 않았다 — 2대뿐이라 1대 손실도
용량 50% 손실이므로 놓치면 안 된다. `ec2-status-check-failed`도 같은 이유로 2 → 3분으로 조정.

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

# 2. 알람 9종 (지표 8 + 복합 1)
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

# 전체 알람 상태 (복합 알람 포함)
aws cloudwatch describe-alarms --region ap-northeast-2 --alarm-name-prefix peakpic- \
  --alarm-types MetricAlarm CompositeAlarm \
  --query '[MetricAlarms[].{name:AlarmName,state:StateValue},CompositeAlarms[].{name:AlarmName,state:StateValue}]' --output table
```

강제 발화 후 잠시 뒤 실제 지표로 재평가되므로 상태는 자동 정상화된다.

## 주의

- Discord 웹훅 URL은 Lambda 환경변수로만 주입한다. 레포·스크립트에 하드코딩 금지.
- 알람 추가 시 `create-alarms.sh`에 함께 기록한다 (콘솔에서만 만들면 재구축 불가).
- 출시 1~2주 후 B그룹 임계값 재조정 + 오탐/미탐 회고 예정.

## 4단계 — 알람 분석 (#230)

알람이 "무엇이 임계값을 넘었다"까지만 말한다면, 그 다음 질문("누가 어떤 경로를 불러 무슨 예외가 났나, 배포 때문인가,
사용자 영향인가")은 사람이 콘솔·Grafana·로그를 열어 답해 왔다. 2026-09-29 5xx 알람 때 이 조사에 한 시간이 걸렸다.
`peakpic-alert-analyze` Lambda 가 같은 토픽을 구독해 로그·지표·ALB 액세스 로그·배포 기록을 모으고,
모델이 도구를 골라 조사한 뒤 결론을 같은 Discord 채널에 "🔎 분석" 임베드로 따라 붙인다(1~2분 뒤).

```
CloudWatch 알람 ─▶ SNS(peakpic-alarms) ─┬─▶ peakpic-alert-notify   ─▶ 🚨 발생 (즉시)
                                        └─▶ peakpic-alert-analyze  ─▶ 🔎 분석 (ALARM 전이만, 필터 정책)
```

| 파일 | 역할 |
|---|---|
| `setup-analyze.sh` | 읽기 전용 역할·패키지·함수·필터 구독 생성 (멱등). `LOG_GROUP`·`DISCORD_WEBHOOK_URL` 필요 |
| `../../lambda/alert-analyze/` | 도구 4종·플레이북·조사 루프·골든 셋. 설계와 검증 방법은 그 README |

원칙: 읽기 전용(자동 조치 없음), 수치 없는 근거는 코드가 버림, 모델이 실패해도 수집한 원자료는 올림, 과거 알람 3건 골든 셋으로 채점.

결론이 앱 결함·배포 회귀면 GitHub 이슈까지 만든다(#233, `ISSUE_CREATE=shadow` 기본 → 정밀도를 잰 뒤 `on`).
`on` 은 이슈 쓰기 PAT 시크릿(`peakpic/github-issue-token`)이 유일한 쓰기 권한이며 이 레포의 Issues 에 한정된다. 규칙은 그 README "이슈 자동 생성".
