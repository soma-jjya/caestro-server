# 시그널링 부하테스트 (#90)

출시 전 두 가지 질문에 숫자로 답한다.

1. **성능**: relay 한 건이 서버 안에서 어디에 시간을 쓰는가 → 병목 확정 → 개선 전/후 비교
2. **용량**: 현재 인프라(t3.micro×2)가 동시 세션 몇 개까지 버티는가

## 측정 구조 — 3단 분해

```
k6 (E2E 편도)  =  네트워크  +  서버 내부 (ws_message_handle)  ⊃  Redis 왕복 (lettuce_command)
```

- **k6**: 1 VU = 세션 1개. 한 VU가 owner/participant 소켓 2개를 모두 열므로 두 소켓이 같은 시계를
  공유한다 → 페이로드에 심은 전송 시각과 수신 시각의 차 = 발신 소켓→서버→수신 소켓 **편도 지연**.
  타임스탬프는 별도 필드가 아니라 candidate foundation / SDP 속성 안에 심는다
  (서버가 SignalingRequest로 재직렬화해 중계하므로 스키마 밖 필드는 유실됨).
- **서버 내부**: `ws_message_handle_seconds`(타입별 처리시간), `lettuce_command_completion_seconds`
  (명령별 Redis 왕복) — #89에서 추가한 계측. Grafana에서 k6 수치와 대조한다.

## 빠른 시작

```bash
# 1. 로컬 스펙 제한 환경 기동 (앱 :8081, Grafana :3001, Prometheus :9091)
cd load-test
printf '%s' '<application-dev.yml의 metrics.token 값>' > monitoring/metrics_token
docker compose -f docker-compose.loadtest.yml up -d --build

# 2. 스모크 (세션 2개 — 스크립트/환경 검증)
k6 run -e SCENARIO=smoke k6/signaling-session.js

# 3. 성능 측정 — 고정 부하 (before/after 비교의 기준선)
k6 run -e SCENARIO=fixed -e SESSIONS=100 -e DURATION=5m k6/signaling-session.js

# 4. 용량 한계 — 계단식 램프 (50→150→300→500 세션)
k6 run -e SCENARIO=ramp k6/signaling-session.js
```

개발용 앱(bootRun, :8080)에 겨냥하려면: `-e API=http://localhost:8080`

## 시나리오와 파라미터

| 환경변수 | 기본값 | 의미 |
|---|---|---|
| `SCENARIO` | smoke | smoke(검증) / fixed(성능) / ramp(용량) |
| `SESSIONS` | 50 | fixed의 동시 세션 수 |
| `DURATION` | 3m | fixed의 유지 시간 |
| `SESSION_SEC` | 40 (smoke 8) | 세션 1개 수명 — 이후 END_SESSION |
| `ICE_BURST` | 10 | 연결 수립 시 측당 ICE 후보 수 |
| `SDP_KB` | 4 | OFFER/ANSWER 크기. **9 이상 → 톰캣 인바운드 한도(기본 8KB) 실험** |
| `JITTER` | 1 | 0이면 시작·수명 지터 해제 → **전 VU 동시 몰림(스파이크) 조건 재현** |
| `RECONNECT` | 1 | 예기치 않은 close 시 재연결 계약대로 재접속(#105). 0이면 close = 세션 실패(#90 당시 동작) |
| `PROBE_SEC` | 0 | >0이면 수립 후 양쪽이 N초마다 ICE 1건 전송 → 재접속 공백 중 유실을 `sent−received`로 측정 |
| `RESUME_TIMEOUT_SEC` | 15 | 최초 close부터 `SESSION_RESUMED`까지 허용 시간. 로컬 컨테이너 재기동 실험은 60 권장 |

세션 흐름: 게스트 로그인(VU당 1회) → CREATE → JOIN → DEVICE_SPEC×2 → OFFER/ANSWER →
ICE 버스트(양측) → PING 25s 유지 → END_SESSION.

재연결 계약(k6가 앱 대신 수행): 1012(서버 재시작)는 0~1s 안에 즉시(연속 2회까지), 그 외 코드는
`min(1s·2^n + random(0~1s), 30s)` backoff 후 **같은 토큰**으로 `JOIN_SESSION` → 서버 takeover →
`SESSION_RESUMED`. 복귀 시 ANSWER를 못 받은 상태면 디렉터(owner)가 OFFER를 재발신한다.

## 지표 읽는 법

| k6 지표 | 대조할 서버 지표 | 해석 |
|---|---|---|
| `relay_ice_e2e_ms` | `ws_message_handle{type="ICE_CANDIDATE"}` | 차이 = 네트워크+큐잉. 서버 몫이 크면 내부 병목 |
| `relay_sdp_e2e_ms` | `ws_message_handle{type="OFFER\|ANSWER"}` | ICE와의 격차 = 페이로드 크기 비용 |
| `create_session_ms` / `join_established_ms` | `ws_message_handle{type="CREATE\|JOIN..."}` + `hikaricp_connections_pending` | DB 동기 경로. pending 발생 = 풀(10) 포화 |
| (서버) `lettuce_command` P95·건수 | — | 명령별 왕복. **건수/중계건수 비율 = 메시지당 Redis 왕복 수** |
| `relay_msgs_sent` vs `relay_msgs_received` | `ws_relay_no_receiver_total` | 유실 감지 |
| `ws_unexpected_close{code,phase}` | `ws_close_total{code}` | 예기치 않은 종료 — 코드별(1006 비정상 / 1001 going away / 1012 드레인)·단계별(수립 중/완료) |
| `unexpected_close_epoch_ms` | `ws_connections_active` 절벽 | max−min = 종료가 흩어진 폭. 드레인 jitter의 증거 |
| `reconnect_resume_ms` / `resume_success` | `ws_join_total{result="takeover_*"}` | 최초 close → `SESSION_RESUMED` = **복원 시간**, 복원 성공률 |
| `reconnect_attempt_failed` | — | 재접속 시도 자체가 실패한 횟수(서버 부재). 로컬 재기동 실험에선 정상적으로 발생 |
| `peer_disconnected_seen` / `peer_reconnected_seen` | — | 상대 화면이 본 이탈/복귀 통지 — 깜빡임 UX 판단 근거 |

## 배포 중 세션 생존 측정 (#105)

세션이 살아 있는 동안 서버를 죽이고, 재연결 계약을 따르는 k6가 얼마나 빨리·얼마나 많이 복원되는지 잰다.
`SESSION_SEC`은 서버 교체 시간보다 충분히 길게(로컬 150s, 운영 300s) 잡아야 세션이 교체를 "겪는다".

```bash
# 로컬 — close 코드·재접속 동작 검증. 복원 시간엔 컨테이너 재기동(~20s)이 섞이므로 참고치.
k6 run -e SCENARIO=fixed -e SESSIONS=20 -e DURATION=3m -e SESSION_SEC=150 -e PROBE_SEC=2 \
  -e RESUME_TIMEOUT_SEC=60 --summary-export=results/<날짜>/local-restart.json k6/signaling-session.js
# (1분 뒤, 세션이 전부 수립된 상태에서) — stop 타임아웃을 넉넉히: compose restart는 ~1초 만에 SIGKILL을
# 보내 드레인(jitter 3s)을 중간에 끊는 것이 실측됨 (#106 1차 after). 운영의 docker --stop-timeout 30과 같은 조건
docker stop -t 30 lt-app && docker start lt-app

# 운영 — 진짜 롤링 교체. 알람 비활성(위 절차) 후 실행, 3분 뒤 같은 이미지로 refresh 재실행
k6 run -e SCENARIO=fixed -e SESSIONS=50 -e DURATION=12m -e SESSION_SEC=300 -e PROBE_SEC=2 \
  -e API=https://<운영 도메인> --summary-export=results/<날짜>/prod-rolling.json k6/signaling-session.js
aws autoscaling start-instance-refresh --auto-scaling-group-name peakpic-asg \
  --preferences '{"MinHealthyPercentage":100,"InstanceWarmup":120}'
```

운영에서 배운 것(2026-08-30, results/2026-08-30-107-prod/NOTES.md): 롤링 배포의 끊김은 SIGTERM이 아니라
**ALB 등록 해제 지연 만료**에서 일어난다(1006 일괄, SIGTERM은 그 뒤). 그래서 앱은 IMDS `target-lifecycle-state`를
폴링해 등록 해제 시작 시점에 드레인한다(#111). 운영 측정은 이 경로가 동작하는지(1012 비율·분산)를 본다.

읽는 순서: `ws_unexpected_close` 코드 분포(어떻게 죽었나) → `reconnect_resume_ms` p95(얼마나 빨리 돌아왔나)
→ `resume_success`(다 돌아왔나) → `relay_msgs_sent−received`(공백 중 뭘 잃었나) → Grafana
`ws_connections_active` 인스턴스별 절벽/복구 곡선.

## 스모크에서 이미 확인된 사실 (2026-08-24, 무부하 로컬 dev)

- 중계 48건에 **PEXPIRE 정확히 146회** = 48×3 + JOIN TTL 연장 2회 → "relay 1건 = Redis 순차
  5왕복(GET 2 + PEXPIRE 3)" 코드 분석이 실측으로 확정됨.
- GET 평균 0.59ms, PEXPIRE 평균 0.38ms → 순차 왕복 합 ≈ 2.3ms ≈ ICE 서버 내부 처리 3.3ms의
  대부분. **relay 처리시간은 사실상 Redis 왕복 대기 시간이다.**
- SDP(4KB) E2E 15.3ms vs ICE E2E 5.0ms — 같은 경로인데 페이로드 크기로 3배 차이.

## 운영 최종 검증 (개선 후 1회, 10~15분)

로컬 수치는 상대 비교용이다(네트워크·CPU 크레딧이 다름). 절대값은 운영에서 1회만 확인한다.

```bash
# 알람 액션 일시 비활성 (부하로 인한 오탐 방지) — 종료 후 반드시 복원
aws cloudwatch disable-alarm-actions --region ap-northeast-2 \
  --alarm-names $(aws cloudwatch describe-alarms --region ap-northeast-2 \
    --alarm-name-prefix peakpic- --query 'MetricAlarms[].AlarmName' --output text)

k6 run -e SCENARIO=fixed -e SESSIONS=<로컬 확인치의 1/2> -e DURATION=10m \
  -e API=https://<운영 도메인> k6/signaling-session.js

aws cloudwatch enable-alarm-actions --region ap-northeast-2 \
  --alarm-names $(aws cloudwatch describe-alarms --region ap-northeast-2 \
    --alarm-name-prefix peakpic- --query 'MetricAlarms[].AlarmName' --output text)
```

t3 CPU 크레딧 잔고를 시작 전/후로 확인할 것 — 크레딧을 태우는 테스트는 이후 실사용 성능에 영향을 준다.

## 알려진 한계

- 타임스탬프 해상도 1ms(`Date.now()`) — 무부하 수치는 하한이 뭉개질 수 있으나 부하 구간(ms대)에선 충분.
- 로컬 `cpus: 2.0`은 t3.micro의 **버스트 상태** 근사 — 크레딧 고갈(기준선 10% 강등)은 재현 불가.
- ramp 하강 구간의 강제 종료는 `session_success`에 실패로 집계됨 — 임계 판정은 상승 구간으로 본다.
