# 푸시 알림: 기기 토큰 · FCM 발송 · 네 칸 완성 (#206)

함께 채우기 주인은 링크를 보내고 앱을 닫는다. 네 칸이 다 찬 것을 폰에 띄워 주는 것이 1단계의 전부다.
화면을 보고 있을 때는 푸시가 아니라 폴링(contracts/fill-together.md "세션 조회와 폴링")이 담당한다.

## 인증·경로

모두 Bearer JWT. 게스트 계정도 된다(users 행이 있으므로). 웹 참여자(브라우저)는 대상이 아니다 — 웹 푸시는 별도 체계.

| 메서드 | 경로 | 본문 / 쿼리 | 응답 |
|---|---|---|---|
| PUT | `/users/me/push-token` | `{ "platform": "ANDROID" \| "IOS", "token": "<FCM 토큰>" }` | 200 `{id, platform, createdAt, lastSeenAt}` |
| DELETE | `/users/me/push-token?token=<FCM 토큰>` | | 204 · 404 내 토큰 아님 |
| POST | `/users/me/push-token/test` | | 200 `{tokens, sent}` — 내 활성 토큰 수와 FCM 이 받아 준 수 |

- 등록은 멱등이다. 앱 시작·로그인·토큰 갱신 때마다 보낸다. 같은 토큰이 다른 계정에 묶여 있었으면 이 계정으로 옮겨 온다(기기 하나 = 마지막 로그인 계정).
- 한 사용자가 기기 여럿이면 행 여럿이고 전부에 보낸다.
- FCM 이 "없는 기기"(UNREGISTERED)라고 답한 토큰은 지우지 않고 끈다(`disabled_at`). 같은 토큰이 다시 등록되면 되살아난다.

## 알림 종류와 payload

앱은 `data.type` 으로 어디로 갈지 정한다. 제목·본문은 서버가 정하고 앱은 그대로 보여 준다.

| type | 언제 | data | 앱 동작 |
|---|---|---|---|
| `fill_completed` | 함께 채우기 네 칸이 **처음** 모두 찬 순간, 세션 주인에게 **세션당 1회** | `{ "type": "fill_completed", "code": "K7X2MQ" }` | 탭 → 그 세션 화면. 포그라운드면 배너 대신 세션을 즉시 다시 조회 |
| `test` | `POST /users/me/push-token/test` | `{ "type": "test" }` | 아무 데도 안 감 |

FCM 메시지는 `notification{title, body}` + `data` 를 함께 싣고, Android `priority: HIGH`, iOS `apns-priority: 10`.

## 서버 동작 (읽는 순서)

1. **토큰**: `push_tokens` (`PushToken`, `PushTokenService`). 토큰 전역 유니크.
2. **발송기 `PushSender`**: 운영은 `FcmPushSender`(FCM HTTP v1, 서비스 계정 키로 액세스 토큰 — Google Play 결제 확인과 같은 방식, SDK 없음),
   로컬·CI 는 `NoopPushSender`(보내지 않고 로그). 예외를 던지지 않고 `PushResult`(SENT · UNREGISTERED · FAILED · DISABLED)로 답한다.
   호출은 서킷브레이커 `fcm` 뒤. 4xx(없는 토큰·잘못된 요청)는 FCM 장애가 아니라 서킷 표본에서 빼고 5xx·네트워크만 센다.
3. **`PushService.sendToUser`**: 활성 토큰 전부에 보내고 `push.sent{type,result}` 로 센다. FCM 을 부르는 동안 DB 트랜잭션을 쥐지 않는다.
4. **네 칸 완성**: `FillService.fillSlot`/`restoreQueued` 가 칸을 바꾼 뒤 네 칸이 다 찼으면 `fill_sessions.completed_notified_at` 을
   **조건부 UPDATE**(`is null` 일 때만)로 표시하고 1건 바뀌었을 때만 `FillCompletedEvent` 를 발행한다 — 동시 요청 둘이 모두 "다 찼다"를 봐도 한쪽만.
   `FillCompletedPushListener` 가 **커밋 뒤**(`@TransactionalEventListener AFTER_COMMIT`) **요청 스레드 밖**(`@Async`, Spring Boot 기본 실행기)에서 보낸다.
   커밋 뒤라 "알림은 왔는데 칸이 비어 있음"이 없고, 비동기라 업로드 응답이 FCM 을 기다리지 않는다.
5. 칸을 비우고 다시 채워도 다시 보내지 않는다(표시가 남아 있다).

## 설정

```yaml
push:
  enabled: false              # true 면 FCM 발송(운영). 로컬·CI 는 Noop
  fcm:
    project-id: ""            # Firebase 프로젝트 ID
    service-account-json: ""  # 서비스 계정 키(JSON 원문 또는 base64). 운영은 Secrets Manager 에 push.fcm.service-account-json
```

처음 한 번(콘솔): Firebase 프로젝트 → 프로젝트 설정 › 클라우드 메시징에서 iOS 는 **APNs 인증 키(.p8) 업로드** → 서비스 계정 키 JSON 발급(역할: Firebase Cloud Messaging API 관리자 또는 편집자)
→ Secrets Manager 에 base64 한 줄로. 운영 yml 에 `push.enabled: true`, `push.fcm.project-id`.

## 잃어도 되는가 — 큐를 두지 않은 이유

발송은 커밋 뒤 메모리 이벤트라, 커밋과 발송 사이에 서버가 재시작되면 그 알림은 사라진다. 1단계의 알림(네 칸 완성, 테스트)은 잃어도 사용자가 손해 보지 않는 종류라 큐를 두지 않았다.
다음 단계로 가는 계기는 셋 중 하나다: 잃으면 안 되는 알림이 생김(결제 유예 안내) · 여러 건을 **묶어서** 보내야 함("친구가 칸을 채웠어요") · 재시작 중 유실이
지표(`push.sent` 대비 발행 이벤트 수)에 보임. 그때는 Redis Streams(이미 있는 Redis, 소비자 그룹·ack·재전송)로 간다. SQS 는 워커를 분리할 때.

## 관측

- `push_sent_total{type,result}` — result 가 failed 로 치우치면 FCM 설정·서킷(`resilience4j_circuitbreaker_state{name="fcm"}`)을 본다.
- 비동기 실행기 지표는 Spring Boot 기본(`executor_*{name="applicationTaskExecutor"}`).

## DDL (ddl-auto 가 update 가 아닌 환경용)

```sql
CREATE TABLE push_tokens (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  platform VARCHAR(10) NOT NULL,
  token VARCHAR(512) NOT NULL,
  created_at DATETIME(6) NULL,
  last_seen_at DATETIME(6) NOT NULL,
  disabled_at DATETIME(6) NULL,
  CONSTRAINT uk_push_tokens_token UNIQUE (token),
  CONSTRAINT fk_push_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
);
ALTER TABLE fill_sessions ADD COLUMN completed_notified_at DATETIME(6) NULL;
```

## 2단계 (별도 이슈)

사진 만료 전날 안내(`fill_expiring`, 만료 배치에 D-1 조회 추가), 친구가 칸을 채웠어요(묶음 발송 → 큐 계기), 결제 유예 안내(잃으면 안 됨 → 큐 계기).
