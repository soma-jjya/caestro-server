# 배포 중 세션 생존 — before (로컬, 2026-08-28)

조건: fixed 20세션 / SESSION_SEC 150 / PROBE_SEC 2 / RESUME_TIMEOUT 60 / 재연결 계약 ON.
세션 전부 수립된 75초 시점에 `docker compose restart app` (드레인 코드 없음 = 현재 운영과 같은 종료 경로).

## 숫자

| 항목 | 값 | 출처 |
|---|---|---|
| 서버가 기록한 종료 | **1001 ×40** "The web application is stopping" | app-shutdown.log |
| 클라이언트가 본 종료 | **1006 ×40** (close 프레임 미도달 = 비정상 종료로 인식) | local-restart-20.json |
| 감지 시간 | 재기동 명령 +185ms — TCP FIN이 도달해 즉시 | restart-timing.log vs close epoch |
| 종료 분산 폭 | **1ms** — 40소켓 동시 종료 → 동시 재접속 | unexpected_close_epoch_ms |
| 재접속 시도 실패 | 68회 (컨테이너 부재 5.5s 동안 backoff 재시도) | reconnect_attempt_failed |
| 복원 | **40/40**, med 8.1s / p95 8.7s | reconnect_resume_ms |
| 유실 | **98 / 5354 (1.8%)** — 먼저 복귀한 쪽이 아직 안 돌아온 상대에게 보낸 프로브 | relay sent−received |
| 상대 통지 | PEER_DISCONNECTED **0**, PEER_RECONNECTED 18/20 | peer_*_seen |
| 종료 순서 결함 | Redis 파괴 **후** WS close → handleDisconnect 40건 전부 `LettuceConnectionFactory was destroyed` | app-shutdown.log |

## 해석

1. **클라이언트는 서버 재시작을 구분할 수 없다.** 서버는 1001을 기록하지만 close 프레임이 클라이언트에
   닿기 전에 연결이 끊겨 1006(비정상)으로 보인다 → 계약상 backoff 경로(즉시 재접속 불가). 복원 시간
   8.1s 중 컨테이너 재기동 5.5s를 빼면 **약 2.6s가 backoff 비용**이다. 1012를 "먼저" 보내면 0이 된다.
2. **종료가 1ms 안에 몰린다** → 재접속도 동시에 몰린다. 운영(2대·ALB)에서는 이 몰림이 남은 인스턴스의
   JOIN 스파이크로 나타난다 (#90에서 본 몰림과 같은 형태). jitter 분산이 필요한 근거.
3. **종료 순서가 거꾸로다.** Spring 종료 훅이 Redis(LettuceConnectionFactory)를 먼저 파괴하고 그 뒤
   Tomcat이 WS를 닫으므로, 소켓 정리(handleDisconnect)가 전부 실패한다. 매핑·상태가 그대로 남고
   PEER_DISCONNECTED도 나가지 않는다. WS를 **빈 파괴 전에** 닫아야 한다 → SmartLifecycle phase의 근거.
4. **유실 1.8%는 재접속 공백의 비대칭에서 온다.** 두 소켓이 같은 순간 죽어도 복귀 시각은 다르고,
   먼저 돌아온 쪽의 메시지는 죽은 소켓 ID로 중계돼 사라진다. SDP/ICE는 재생성, 상태는 스냅샷으로
   복구하므로 계약(디렉터 OFFER 재발신·DEVICE_SPEC 재전송)으로 흡수한다.

## 이전 가정의 수정

"앱이 최대 90초 모른다"는 docker stop 경로에선 성립하지 않았다 — 프로세스가 죽으며 FIN이 나가 즉시
감지된다. 90초 경로는 FIN 없이 사라지는 경우(하드웨어 장애·네트워크 단절)에만 해당한다. 드레인의
가치는 감지 시간이 아니라 **①구분 가능한 코드 ②분산 ③정리 순서 ④계약**에 있다.

## 한계

- 복원 시간엔 컨테이너 재기동 ~5.5s가 섞여 있다(운영은 ALB가 즉시 다른 인스턴스로 보냄). 절대값이 아니라
  after와의 **구성 비교**(backoff 몫·분산·정리 실패 0)로 읽을 것.
- 단일 인스턴스라 크로스 인스턴스 세션의 생존은 운영 측정(prod-rolling)에서 본다.
