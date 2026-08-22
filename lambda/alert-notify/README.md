# 알림 중계 Lambda

SNS로 들어온 CloudWatch 알람·배포 이벤트를 Discord 임베드로 변환해 게시한다.
전체 구성과 알람 정의는 `infra/alerting/README.md` 참고.

## 구성

| 파일 | 역할 |
|---|---|
| `format.py` | 메시지 → Discord 임베드 변환 순수 로직 (AWS 무관, 로컬 테스트 가능) |
| `handler.py` | Lambda 진입점. SNS 레코드 파싱 → 변환 → 웹훅 POST |
| `local_test.py` | 로컬 검증 (AWS 없이 5개 케이스 변환 확인) |

의존성 없음(표준 라이브러리만) — 컨테이너 대신 zip으로 배포한다.

## 처리하는 메시지

| 입력 | 출력 |
|---|---|
| CloudWatch 알람 ALARM | 🚨 빨강 — 알람명·조건·판정 근거·시각(KST) + 콘솔 링크 |
| CloudWatch 알람 OK | ✅ 초록 — 복구 알림 |
| ASG 인스턴스 새로고침 성공/실패 | ✅ 초록 / ❌ 빨강 — 롤링 교체 결과 |
| 그 외 | ❔ 회색 — 원문 일부를 그대로 전달(유실 방지) |

배포 알림은 `deploy.yml`의 "롤링 교체 시작"과 짝을 이루는 **후반부**다.
GitHub Actions는 refresh 트리거까지만 알 수 있고, 실제 교체 성공 여부는 몇 분 뒤 이 경로로 온다.

## 로컬 테스트

```bash
cd lambda/alert-notify
python3 local_test.py            # 변환 결과만 출력
python3 local_test.py --post     # 실제 Discord 발송 (DISCORD_WEBHOOK_URL 필요, 테스트 채널 권장)
```

## 배포

`infra/alerting/setup-pipeline.sh`가 zip 생성부터 함수 갱신까지 처리한다.
코드만 고쳤을 때도 같은 스크립트를 다시 실행하면 된다.

## 주의

- Discord(Cloudflare)는 기본 `python-urllib` User-Agent를 403으로 차단한다.
  `handler.py`에서 명시 UA를 보내는 이유 — 릴리스 알림에서 겪은 실제 사고다.
- 웹훅 URL은 Lambda 환경변수로만 주입한다. 레포에 넣지 않는다.
