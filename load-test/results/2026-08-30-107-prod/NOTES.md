# 배포 중 세션 생존 — 운영 1차 (2026-08-30 00:12~00:25 KST, #107)

조건: fixed 50세션 / SESSION_SEC 600 / PROBE_SEC 2 / 재연결 계약 ON. 세션 수립 후 같은 이미지로
instance refresh(MinHealthyPercentage 100, InstanceWarmup 120). 인스턴스엔 #106 드레인 + `--stop-timeout 30`,
대상그룹 deregistration delay 30s 적용 상태.

**결과: 드레인이 클라이언트에 닿은 적이 없다.** 1012 0건, 1006 150건. 이 파일은 "운영 before"로 쓴다.

## 타임라인 (ASG 활동 기록 + k6 close 시각, KST)

| 시각 | 사건 | 출처 |
|---|---|---|
| 00:13:46 | refresh 트리거 | CloudShell |
| 00:13:55.9 | **Terminating** i-0cd7…(옛 #1) 시작 — 트리거 9초 뒤 | scaling-activities |
| 00:13:57.5 | Launching i-06eb…(새 #1) 시작 — **종료가 기동보다 1.6초 먼저** | scaling-activities |
| 00:14:26.6 | 옛 #1의 소켓 50개 1006으로 끊김 — Terminating 시작 +30.7s = **등록 해제 지연(30s) 만료** | k6 close epoch |
| 00:15:08 | 옛 #1 종료 완료 (SIGTERM → 드레인은 이 사이 어딘가, 이미 죽은 소켓에) | scaling-activities |
| 00:18:42.6 | Terminating i-073a…(옛 #2) 시작 | scaling-activities |
| 00:19:13.7~14.1 | 옛 #2의 소켓 100개(옮겨온 50 포함) **400ms 안에** 1006 — +31s | k6 close epoch |
| 00:19:35 | 옛 #2 종료 완료 | scaling-activities |

두 묶음 모두 "Terminating 시작 + 30초"에서 끊겼고 폭이 400ms → 우리 드레인(jitter 3s)이 아니라 **ALB가 등록 해제
완료 시점에 일괄 종료**한 것. AWS 문서: "기존 연결은 등록 해제 지연이 만료될 때까지만 유지된다 → 그 뒤 인스턴스 종료".
SIGTERM(→우리 드레인)은 그 뒤에 온다.

## 숫자

| 항목 | 값 |
|---|---|
| 종료 코드 | 1006 ×150 (1012 = 0) |
| 종료 분산 | 묶음당 ≤400ms (설정 jitter 3,000ms 무효) |
| 재접속 시도 실패 | 0 — ALB가 즉시 헬시 인스턴스로 라우팅 ✓ |
| 복원 | **76/150 (50.7%)** — 방장만 takeover, 참여자는 슬롯 해제 → 신규 입장 → SESSION_RESUMED 없음 → 30s 타임아웃 |
| 복원 시간 (성공분) | med 2.0s / p95 2.19s — 1006 → backoff 경로. 1012였다면 0~1s |
| 세션 성공 | 3/77 (3.9%) |
| 유실 | 481 / 39,876 (1.2%) |
| 이중 탈출 | 50소켓 (150 − 100) |
| PEER_DISCONNECTED | 69 — 서버가 일반 이탈 경로로 처리했다는 증거 (드레인 플래그 미점등) |
| 게스트 로그인 | http p90 4.4s (100건/20s 몰림, 기지) |

## 결론 두 가지

1. **끊김의 트리거는 SIGTERM이 아니라 ALB 등록 해제다.** 드레인은 등록 해제가 시작되는 순간(= 30초 창의 시작)에
   실행돼야 한다. 신호원: IMDS `autoscaling/target-lifecycle-state`(Terminating 진입 시 `Terminated`로 바뀜, AWS가
   "종료 전 코드 실행" 용도로 제공). 앱이 2초 폴링 → `Terminated`면 즉시 드레인. → 서브 이슈로 분리.
2. **refresh가 launch-before-terminate로 동작하지 않았다.** 종료 활동이 기동 활동보다 먼저 시작(이전 배포 2회도 동일).
   MinHealthyPercentage 100만으론 부족 — ASG MaxSize가 desired와 같으면 먼저 띄울 자리가 없다. MaxSize 확대 +
   preferences에 MaxHealthyPercentage 명시 검토. HealthCheckType(EC2 vs ELB)도 확인 대상: 새 인스턴스가 ALB 헬시
   전에 옛 인스턴스가 빠지면 재접속이 남은 옛 인스턴스로만 몰린다(관측: 50개 전부 파랑으로).

## 로컬과 달랐던 이유

로컬(단일 컨테이너)엔 LB가 없어 SIGTERM = 끊김의 시작이었다. 운영은 LB가 앞에 있어 "끊김의 시작 = 등록 해제"이고
SIGTERM은 뒷정리다. 강의의 ③"기존 연결은 잠깐 유지"가 바로 그 30초 창이며, 정돈된 종료는 그 창 **안에서** 해야 한다.
