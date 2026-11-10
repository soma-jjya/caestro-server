# 알람 분석 Lambda (#230)

CloudWatch 알람이 울리면 **사람이 콘솔을 열기 전에** 로그·지표·ALB 접근 기록·배포 기록을 모아
"무슨 일인지 · 사용자 영향 · 유력 원인 · 다음에 확인할 것"을 Discord 에 올린다.

기존 `alert-notify` 가 알람을 즉시 전달하고(전반부), 이 함수가 같은 SNS 토픽을 구독해 1~2분 뒤 분석을 따라 붙인다(후반부).

```
CloudWatch 알람 ─▶ SNS(peakpic-alarms) ─┬─▶ alert-notify  ─▶ Discord "🚨 발생"        (즉시)
                                        └─▶ alert-analyze ─▶ Discord "🔎 분석 · 알람 제목 · 서비스" (1~2분 뒤)
                                              │ 선조회(플레이북) → 모델이 도구를 골라 추가 조사 → submit_report
                                              ├─ query_logs          CloudWatch Logs Insights
                                              ├─ query_metrics       CloudWatch 지표(허용 목록)
                                              ├─ query_access_logs   S3 ALB 액세스 로그
                                              └─ recent_deploys      ASG refresh·활동 + GitHub 커밋
```

## 왜 이 구조인가

- **LLM 이 아니라 그 주변이 가치다.** 도구 4종은 LLM 없이 단독 호출·테스트되고(`local_test.py`), 결론은 strict 스키마의
  `submit_report` 도구로만 받으며, 수치가 없는 근거 항목은 코드가 버린다(`investigate._clean_report`). 그럴듯한 추측이
  알람 채널에 박히는 것이 알람이 없는 것보다 나쁘기 때문이다.
- **알람별 플레이북.** 사람이 처음 묻는 질문은 알람마다 다르다(`playbooks.py`). 5xx 는 "누가 어떤 경로를 불러 무슨 예외가 났나",
  언헬시 호스트는 "배포 중인가, 기동 실패인가". 그 질문에 답하는 도구를 모델 호출 전에 미리 돌려 왕복을 아낀다.
  앱 로그에 서사가 있는 3종(5xx·언헬시 호스트·상태검사)은 추가 조사를 허용하고, 나머지 4종은 지표 요약만 한다.
- **읽기 전용.** IAM 은 조회 권한만. 자동 조치(롤백·차단)는 하지 않는다.
- **폴백.** 모델이 결론을 못 내거나 예외가 나면 선조회한 원자료 요약이라도 올린다. 분석 실패가 침묵이 되면 안 된다.
  함수는 예외를 밖으로 던지지 않는다 — Lambda 비동기 재시도로 같은 분석이 두 번 올라가는 것을 막기 위해서다.

## 파일

| 파일 | 역할 |
|---|---|
| `handler.py` | 진입점. SNS 레코드 중 CloudWatch 알람의 ALARM 전이만 조사. API 키는 환경변수 또는 Secrets Manager |
| `tools.py` | 도구 4종 구현(`AwsTools`)과 모델에 주는 스키마(`TOOL_SPECS`). ALB 로그 파서·집계는 순수 함수 |
| `playbooks.py` | 알람 이름 → 선조회 목록 + 조사 안내 + 깊이(full/summary) |
| `investigate.py` | 조사 루프(Anthropic Messages API, 도구 왕복 상한 6회·입력 토큰 15만 상한), `submit_report` 스키마, 근거 필터 |
| `report.py` | 결론 → Discord 임베드. 실패 시 원자료 임베드. 제목은 `../alert-notify/alarm_catalog.py` 의 사람 말 알람 이름표를 쓴다(#254, `setup-analyze.sh` 가 패키지에 복사) |
| `redact.py` | 도구 결과의 토큰·키·이메일·전화번호 마스킹(모델에 보내기 전) |
| `triage.py` | 이슈 자동 생성(#233)의 문지기·지문·본문. 순수 함수 |
| `github_issues.py` | 이슈 찾기·만들기·재발 코멘트(#233). `ISSUE_CREATE` 모드별 동작 |
| `fixtures.py` | AWS 없이 도구를 대신하는 대역(골든 셋·로컬 테스트용) |
| `local_test.py` | AWS·LLM 없이 전 구성요소 검증(가짜 모델로 루프까지) |
| `eval/replay.py` | 골든 셋 재생 — 실제 모델이 사람의 결론과 같은지 채점 |
| `eval/cases/*.json` | 과거 알람 3건(알람 메시지 + 도구 결과 픽스처 + 기대 결론) |

## 골든 셋

알람 빈도가 낮아 실전 데이터를 기다릴 수 없다. 과거 사람이 조사한 알람 3건을 픽스처로 재현해 모델 결론을 채점한다.

| 케이스 | 알람 | 사람의 결론 | 기대 |
|---|---|---|---|
| 2026-09-18 | alb-5xx-spike | 스캐너가 `/.env`·`/wp-login.php` 등 없는 경로 28건, 1개 IP | external_scanner / none |
| 2026-09-29 | alb-5xx-spike | 스캐너가 없는 `POST /auth/callback` 24건 + 멀티파트 2건 (#137·#138 계기) | external_scanner / none |
| 2026-09-01 | alb-unhealthy-host | #111 릴리스 새 인스턴스 기동 실패(`No default constructor`), 구버전 1대 헬시 유지 | deploy_regression / none |

```bash
python3 local_test.py                       # AWS·LLM 없이 (CI 에서도 가능)
export ANTHROPIC_API_KEY=...
python3 eval/replay.py                      # 3건 재생, 토큰·소요시간 출력
python3 eval/replay.py --runs 3             # 일관성 확인
```

판정 기준: `category`·`user_impact` 가 기대 안에 있고, 핵심 키워드(`mentions_any`) 중 하나 이상이 보고서에 있고, 근거가 1건 이상.
`expect.issue` 로 이슈 문지기의 생성 여부도 채점한다 — 9/1 기동 실패는 **생성**, 스캐너 2건은 **생성 안 함**.

2026-11-04 실측(`--runs 2`, claude-opus-5-5, effort medium): **6/6 통과**, 총 토큰 입력 50,637 / 출력 9,850.

| 케이스 | 왕복 | 입력/출력 토큰 | 소요 |
|---|---|---|---|
| 2026-09-01 기동 실패 | 2 | 12,202/1,801 · 12,119/1,589 | 22.6s · 15.8s |
| 2026-09-18 스캐너 | 1 | 6,330/1,580 · 6,330/1,577 | 17.9s · 15.8s |
| 2026-09-29 auth/callback | 1 | 6,828/1,693 · 6,828/1,610 | 16.8s · 16.6s |

스캐너 2건은 선조회만으로 1회에 끝났고, 기동 실패는 모델이 지표를 한 번 더 조회했다. 두 번 돌린 결론 문장이 서로 같은 뜻이었다.

## 이슈 자동 생성 (#233)

결론이 **우리 코드 결함·배포 회귀**면 Discord 에서 끝내지 않고 GitHub 이슈로 옮긴다(알람 → 조사 → 티켓). 가치는 생성 자체가
아니라 "언제 만들고 언제 만들지 않는지"다 — 아무도 안 보는 이슈 더미가 되면 안 되므로, 아래 셋을 **모두** 만족할 때만 만든다.

| 문지기 | 기준 | 어디서 |
|---|---|---|
| 분류 | `app_bug` 또는 `deploy_regression` | 모델 보고서 `category` |
| 근거 | 수치가 붙은 근거 2건 이상 | `_clean_report` 통과 후 `evidence` |
| 예외 | 스택에 우리 패키지(`com.caestro`)가 보이는 예외 1건 이상 | 조사 중 읽은 `query_logs` 결과의 `stack_trace` |

스캐너·오탐·용량·원인 미상은 이슈가 생기지 않는다. 같은 문제는 한 이슈로 모은다:

- **지문** = 최상위 예외 클래스 + 치환한 경로(숫자 → `{n}`, UUID·긴 16진수 → `{id}`) + 알람 이름 → 8자리 해시. 본문 끝에 `지문 fp:…` 로 적고 검색(`in:body`)으로 찾는다.
- 같은 지문의 **열린** 이슈가 있으면 새로 만들지 않고 "N회째 · 시각 · 건수" 코멘트를 단다. 재발 횟수가 우선순위 근거다.
- 사람이 `auto-triage:false-positive` 라벨을 달면 그 지문은 이후 코멘트도 달지 않는다(닫힌 이슈에 달아도 된다).
- 검색 색인이 몇 초~몇 분 늦을 수 있어, 같은 알람이 연달아 두 번 울리면 드물게 중복이 날 수 있다. 그때는 하나를 닫으면 된다.

본문(공개 레포): 모델 보고서(결론·근거·확인할 것)와 예외 클래스·우리 코드 프레임 3줄·치환한 경로로만 조립한다. 로그 원문은 넣지 않는다.
IP·계정 ID·User-Agent·ARN 과 환경변수로 받은 인프라 이름(로그 그룹·버킷·ASG 등)은 `triage.scrub` 이 지우고, 토큰·이메일·전화번호는
`redact` 가 지운다(`local_test.test_issue_body_has_no_secrets`). 머리말에 "자동 생성 · 사람 확인 필요", 라벨 `auto-triage` + `bug`.

모드 `ISSUE_CREATE`:

| 값 | 동작 |
|---|---|
| `shadow` (기본) | 문지기·지문·찾기까지만 하고 **만들지 않는다**. 임베드에 "📝 이슈 (섀도)" 칸으로 "만들 것 · 제목 · 새 이슈/#N 에 코멘트" 또는 "만들지 않음 · 이유"를 보여 정밀도·재현율을 잰다. 공개 레포라 토큰 없이 돈다 |
| `on` | 실제로 만든다. fine-grained PAT(이 레포 한정, Issues 쓰기만)를 Secrets Manager(`GITHUB_SECRET_ID`)에 둔다. 문지기 탈락은 칸을 붙이지 않는다 |
| `off` | 아무것도 하지 않는다 |

어떤 실패(토큰 없음·GitHub 오류)도 분석 알림을 막지 않는다 — 임베드에 "실패 · 이유" 한 줄로 남긴다.

섀도 운영 기록(정밀도 = 만들겠다고 한 것 중 맞은 비율, 재현율 = 만들었어야 한 것 중 잡은 비율):

| 기간 | 분석 건수 | 만들 것 | 맞음 | 만들지 않음인데 만들었어야 | 판단 |
|---|---|---|---|---|---|
| (섀도 반영 뒤 기록) | | | | | |

## 설치

`infra/alerting/setup-analyze.sh` 가 역할·정책·패키지·함수·SNS 구독(ALARM 전이만 받는 필터 정책)을 멱등으로 만든다.
관리 권한 환경(CloudShell)에서 실행한다. 자세한 전제와 환경변수는 그 스크립트 머리말과 `infra/alerting/README.md`.

| 환경변수 | 뜻 |
|---|---|
| `LOG_GROUP` | 앱 로그 그룹 이름 |
| `ALB_LOG_BUCKET` / `ALB_LOG_PREFIX` | ALB 액세스 로그 버킷·접두(설치 스크립트가 ALB 속성에서 자동 탐색) |
| `ALB_DIM` / `TG_DIM` / `ASG_NAME` / `CACHE_ID` / `RDS_ID` | 지표 차원(설치 스크립트가 채움) |
| `GITHUB_REPO` | `owner/repo`. 공개 레포라 토큰 없이 main 커밋을 읽고 이슈를 찾는다 |
| `ISSUE_CREATE` | 이슈 자동 생성 모드 `shadow`(기본)·`on`·`off` (#233) |
| `GITHUB_SECRET_ID` 또는 `GITHUB_TOKEN` | `on` 일 때 이슈를 쓸 fine-grained PAT. 설치 스크립트는 시크릿이 있을 때만 붙인다 |
| `ANTHROPIC_SECRET_ID` 또는 `ANTHROPIC_API_KEY` | 모델 API 키. 운영은 Secrets Manager 권장 |
| `DISCORD_WEBHOOK_URL` | 분석을 올릴 채널 |
| `MODEL` | 기본 `claude-opus-5-5` |
| `EFFORT` | 기본 `medium` |
| `FALLBACKS` | `off` 면 서버측 모델 폴백을 끈다 |

## 비용·상한

- 모델 왕복 상한 6회, 호출당 출력 4,000 토큰, 누적 입력 15만 토큰. 도구 결과는 7,000자에서 자른다.
- Lambda 제한 시간 150초(Logs Insights 질의 1회 최대 25초 대기).
- 알람 1건당 토큰은 골든 셋 실측 기준 입력 6,330~12,202 / 출력 1,577~1,801, 소요 15.8~22.6초(위 표). 실전 수치는 임베드 footer 에 매번 찍힌다.

## 범위 밖(후속)

- 소스 코드 조회 도구: 스택 트레이스가 우리 코드를 가리키는 사례가 생기면 추가
- Prometheus(모니터링 EC2, 프라이빗) 조회: Lambda 를 VPC 에 넣어야 해서 2단계
- 자동 조치: 하지 않는다
