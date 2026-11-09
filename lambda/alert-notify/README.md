# 알림 중계 Lambda

SNS로 들어온 CloudWatch 알람·배포 이벤트를 Discord 임베드로 변환해 게시한다.
전체 구성과 알람 정의는 `infra/alerting/README.md` 참고.

## 구성

| 파일 | 역할 |
|---|---|
| `format.py` | 보낼지 판단(`should_notify`) + 메시지 → Discord 임베드 변환 (AWS 무관, 로컬 테스트 가능) |
| `handler.py` | Lambda 진입점. SNS 레코드 파싱 → 판단 → 변환 → 웹훅 POST |
| `local_test.py` | 로컬 검증 (AWS 없이 10개 케이스: 전이 판단·단위 표기·복합 알람·배포 결과) |

의존성 없음(표준 라이브러리만) — 컨테이너 대신 zip으로 배포한다.

## 보내는 것과 보내지 않는 것 (#249)

| 전이 | 보냄 | 이유 |
|---|---|---|
| → ALARM | ✅ 발생 | 사람이 봐야 한다 |
| ALARM → OK | ✅ 해제 (한 줄) | 발생과 짝을 맞춘다 |
| INSUFFICIENT_DATA → OK | ❌ | 서버 생성·교체 뒤 지표가 돌아온 것. 아무 일도 없었다 |
| → INSUFFICIENT_DATA | ❌ | 지표가 잠깐 끊긴 것. 실제 장애면 다른 알람이 ALARM 으로 온다 |

배포 결과(EventBridge)와 분류되지 않은 메시지는 항상 보낸다.

## 임베드 모양

알람 이름은 `peakpic-{접미사}`(앱) 또는 `peakpic-b2b-{slug}-{접미사}`(축제)다. `ALARM_CATALOG` 가 접미사마다
사람 말 제목·무슨 일인지·손님 영향을 갖고 있고, `classify_alarm` 이 서비스를 가린다. 시스템 이름은 footer 로 내린다.

```
🚨 서버 메모리 부족 · 축제 · yuja                          ← 제목 (카탈로그 제목 · 서비스)
서버 메모리 사용이 3분째 80% 를 넘어요.                      ← 무슨 일
이대로면 서버가 멈출 수 있어요.                              ← 손님 영향
CloudWatch 에서 보기
현재 메모리 88%   기준 80% 초과   지속 3분 연속   시각 (Discord 상대 시간)
⋯ peakpic-b2b-yuja-host-memory-high                         ← footer
```

- **현재값**은 `NewStateReason` 의 `[…]` 데이터포인트 중 가장 최근 값, **기준**은 `Trigger.Threshold` 를 `METRICS` 표의 단위(%, GiB, 대, 건)로 바꾼 것.
- **해제**는 제목 + "정상으로 돌아왔어요" 한 줄.
- **복합 알람**(#231)은 `Trigger` 대신 `AlarmRule` 이 오므로 규칙 안 알람 이름을 카탈로그 제목으로 바꿔 "조건" 한 칸으로 보여준다.
- 카탈로그에 없는 알람은 이름 그대로 제목, `AlarmDescription` 을 본문으로 쓴다(유실 없음).
- 배포 결과: `✅ 배포 반영 완료 · 앱 (B2C)` / `❌ 배포 반영 실패 · …`. `deploy.yml` 의 "🚀 배포 시작" 과 짝을 이루는 **후반부**다.
  GitHub Actions 는 refresh 트리거까지만 알 수 있고, 실제 교체 성공 여부는 몇 분 뒤 이 경로로 온다.

새 알람을 만들면 `ALARM_CATALOG` 에 접미사를 추가한다. 지표가 새것이면 `METRICS` 에 단위도 넣는다.

## 로컬 테스트

```bash
cd lambda/alert-notify
python3 local_test.py            # 터미널 미리보기 + 검증 (전이 판단·제목·칸 값)
python3 local_test.py --json     # 임베드 JSON 그대로
python3 local_test.py --post     # 실제 Discord 발송 (DISCORD_WEBHOOK_URL 필요). footer 에 '시험 전송' 이 붙는다
```

## 배포

`infra/alerting/setup-pipeline.sh`가 zip 생성부터 함수 갱신까지 처리한다.
코드만 고쳤을 때도 같은 스크립트를 다시 실행하면 된다.

## 주의

- Discord(Cloudflare)는 기본 `python-urllib` User-Agent를 403으로 차단한다.
  `handler.py`에서 명시 UA를 보내는 이유 — 릴리스 알림에서 겪은 실제 사고다.
- 웹훅 URL은 Lambda 환경변수로만 주입한다. 레포에 넣지 않는다.
