# 앱 문의하기 (#263)

앱 설정의 "문의하기"가 보내는 요청과, 관리자 페이지(`peakpic.app/admin/support`)가 읽고 답하는 API 의 계약이다.
실시간 채팅이 아니다. **문의 1건 = 대화 스레드**이고, 답변은 알림함(contracts/notifications.md)과 푸시(contracts/push.md)로 알린 뒤 앱이 화면을 열 때 읽는다.

운영 API 기준 주소: `https://api.peakpic.app`.

## 경로와 책임

| 구분 | 백엔드 API | 인증 |
|---|---|---|
| 앱 | `/support/tickets/**` | Bearer JWT. **로그인 계정만** — 게스트·탈퇴 계정은 403 `SUPPORT_LOGIN_REQUIRED` |
| 관리자 | `/admin/support/tickets/**` | `X-Studio-Key`(운영 조회 `/admin/ops/**` 와 같은 키) 또는 ADMIN JWT |

게스트를 막는 이유: 게스트 토큰은 앱을 지우거나 기기를 바꾸면 사라져 자기 문의와 답변을 다시 볼 수 없다.
관리자 FE 는 기존 프록시와 같은 방식으로 `/admin/api/support/**` 를 두고 서버 쪽에서만 키를 붙인다.

## 상태

```
OPEN ──운영자 답변──▶ ANSWERED ──운영자 닫기──▶ CLOSED
 ▲                        │                       │
 └────── 사용자 이어 쓰기 ──┴───────────────────────┘
```

- `OPEN` 운영자 차례. 관리자 목록의 "미답변"은 `status=OPEN`
- `ANSWERED` 사용자 차례. 사용자가 상세를 열기 전까지 `userUnread=true`
- `CLOSED` 운영자가 닫음. 사용자가 이어 쓰면 다시 `OPEN`

## 앱 API

### 문의 작성 `POST /support/tickets` → 201

| 필드 | 필수 | 규칙 |
|---|---|---|
| `category` | 필수 | `BUG` / `ACCOUNT` / `PAYMENT` / `SUGGESTION` / `OTHER` |
| `title` | 필수 | 1~80자 |
| `body` | 필수 | 1~2000자. 첫 메시지가 된다 |
| `appVersion` | 선택 | ≤30자. 앱이 자동으로(예 `1.4.2`) |
| `platform` | 선택 | ≤10자. `ANDROID` / `IOS` |
| `osVersion` | 선택 | ≤40자(예 `Android 14`, `iOS 18.1`) |
| `deviceModel` | 선택 | ≤60자(예 `SM-S928N`, `iPhone16,2`) |

응답은 아래 상세와 같다. 사진은 응답의 `id` 로 따로 올린다(아래).
429 `TOO_MANY_REQUESTS`: 사용자당 하루 5건.

### 내 문의 목록 `GET /support/tickets?cursor=&size=`

```json
{ "items": [ { "id": 12, "category": "BUG", "title": "사진이 안 올라가요", "status": "ANSWERED", "userUnread": true,
              "platform": "ANDROID", "appVersion": "1.4.2", "createdAt": "2026-11-11T09:00:00", "lastMessageAt": "2026-11-11T13:20:00" } ],
  "nextCursor": null }
```

최신순. `size` 기본 20, 최대 50. `nextCursor` 가 null 이면 끝. `userUnread` 가 true 면 "답변 도착" 표시.

### 문의 상세 `GET /support/tickets/{id}`

```json
{ "id": 12, "category": "BUG", "title": "사진이 안 올라가요", "status": "ANSWERED", "userUnread": false,
  "appVersion": "1.4.2", "platform": "ANDROID", "osVersion": "Android 14", "deviceModel": "SM-S928N",
  "createdAt": "2026-11-11T09:00:00", "lastMessageAt": "2026-11-11T13:20:00",
  "messages": [
    { "id": 30, "author": "USER",  "body": "갤러리에서 고르면 멈춰요", "createdAt": "2026-11-11T09:00:00" },
    { "id": 31, "author": "ADMIN", "body": "1.4.3 에서 고쳤어요. 업데이트 뒤 다시 해 보세요.", "createdAt": "2026-11-11T13:20:00" }
  ],
  "attachments": [
    { "id": 7, "url": "https://api.peakpic.app/support/tickets/12/attachments/7", "contentType": "image/jpeg", "sizeBytes": 812345, "createdAt": "2026-11-11T09:00:05" }
  ] }
```

- 열면 `userUnread` 가 false 가 된다(이 요청이 읽음 처리다).
- 남의 id·없는 id 는 404 `SUPPORT_TICKET_NOT_FOUND`.
- `messages` 는 시간순. `author` 로 말풍선 방향을 정한다.

### 이어 쓰기 `POST /support/tickets/{id}/messages` → 201

`{ "body": "..." }` (1~2000자). 상태가 무엇이든 `OPEN` 이 된다. 응답은 상세. 429: 하루 30건.

### 사진

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/support/tickets/{id}/attachments` | 본문이 이미지 바이트(요청당 1장, 네컷 칸 사진과 같은 방식). `Content-Type: image/jpeg` · `image/png` · `application/octet-stream`. 형식은 앞 바이트로 판정 → 201 `{ id, url, contentType, sizeBytes, createdAt }` |
| DELETE | `/support/tickets/{id}/attachments/{attachmentId}` | 204 |
| GET | `/support/tickets/{id}/attachments/{attachmentId}` | 운영(S3)은 짧은 수명의 서명 주소로 **302**, 아니면 바이트. `Authorization` 을 붙여 부르고 302 를 따라간다(서명 주소에는 헤더를 보내지 않는다). `Cache-Control: no-store` |

- 문의당 **최대 5장**(6장째 400 `SUPPORT_ATTACHMENT_LIMIT`), 장당 **5MB**(413 `SUPPORT_ATTACHMENT_TOO_LARGE`), JPEG·PNG 만(400 `FILL_INVALID_IMAGE`).
- 앱은 올리기 전에 긴 변 2000px 정도로 줄여 보내면 된다. 서버는 줄이지 않는다.
- 문의를 만든 뒤 장수만큼 차례로 올린다. 중간에 실패하면 그 장만 다시 올린다.

### 답변 알림

운영자가 답하면 알림함 행 + 푸시 `support_reply` 가 간다.

```json
{ "type": "support_reply", "ticketId": "12", "notificationId": "88" }
```

푸시 탭·알림함 탭 → `PATCH /notifications/{notificationId}/read` → 문의 `ticketId` 상세로. 상세를 열면 `userUnread` 가 풀린다.

## 관리자 API (`X-Studio-Key`)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/admin/support/tickets?status=&category=&cursor=&size=` | 최신순 한 페이지(목록 항목은 앱 목록과 같은 모양). 미답변은 `status=OPEN` |
| GET | `/admin/support/tickets/count?status=OPEN` | `{ "count": 3 }` 배지용 |
| GET | `/admin/support/tickets/{id}` | 상세 + `adminMemo`, `firstAnsweredAt`, `closedAt`, `user: { id, nickname, provider, withdrawn }`. 탈퇴 계정은 `withdrawn=true` 에 닉네임·provider 가 null. 사진 `url` 은 `/admin/support/tickets/{id}/attachments/{attachmentId}` |
| POST | `/admin/support/tickets/{id}/messages` | `{ "body" }` 답변 → 201 상세. 상태 ANSWERED, 커밋 뒤 알림함+푸시 |
| PATCH | `/admin/support/tickets/{id}` | `{ "status": "CLOSED" \| "OPEN", "adminMemo": "..." }` 보낸 것만. `adminMemo` 빈 문자열이면 지움. `ANSWERED` 는 보낼 수 없다(400) |
| GET | `/admin/support/tickets/{id}/attachments/{attachmentId}` | 302(서명 주소) 또는 바이트 |

## 서버 동작 (읽는 순서)

1. 작성: 로그인 계정 확인 → 하루 한도(Redis 고정 창 `rl:support:create:{userId}`) → `support_tickets` + 첫 `support_messages` 한 트랜잭션 → 커밋 뒤 웹훅 한 줄(`[앱 문의 #12] BUG · 제목 · ANDROID 1.4.2` + 관리자 링크. 글 내용·닉네임은 싣지 않는다).
2. 사진: `support_attachments`(메타) + 저장소(`support.attachment-store`: `rds` 는 `support_attachment_blobs`, `s3` 는 `{prefix}/{ticketId}/{storeId}`). 네컷 이미지(#152)와 같은 분리.
3. 답변: 메시지 저장 + `answered(now)` → 같은 트랜잭션에 아웃박스 행(`support_reply`, #272) → 릴레이가 알림함 행 → FCM. 첫 답변이면 `support.reply.first_hours` 에 작성부터 걸린 시간.
4. 이어 쓰기: `touchByUser` 로 OPEN, 커밋 뒤 웹훅 `[앱 문의 #12] 이어 쓰기 · 제목`.
5. 보관: 마지막 메시지 뒤 `cleanup.support.retention-days`(기본 365) 지난 문의를 `cleanup.support.cron`(기본 05:40) 에 사진까지 지운다. ShedLock `supportCleanup`.
6. 탈퇴: `User.withdraw` 가 닉네임·provider 를 지운다. 문의 행은 보관 기간까지 남고 관리자에게 `withdrawn=true` 로 보인다.

## 왜 이렇게

- **스레드**: 한 번 묻고 한 번 답하는 구조는 "그래도 안 돼요"를 새 문의로 만들게 해 운영자가 맥락을 잃는다.
- **실시간 아님**: 답변은 하루 몇 번 오는 일이라 SSE·폴링을 둘 이유가 없다. 푸시가 깨우고 앱이 열 때 읽는다.
- **사진은 요청당 1장**: 네컷 칸 사진과 같은 길이라 앱 코드가 그대로이고, 장별 진행률·재시도가 쉽다.
- **본문 바이트 판정**: 브라우저·OkHttp 가 Content-Type 을 빼먹는 일이 있다(#152 와 같은 이유).

## 관측

- `support.ticket.created{category}`, `support.message.created{author}`, `support.rejected{reason=guest|create_limit|message_limit}`
- `support.reply.first_hours`(DistributionSummary): 작성부터 첫 답변까지. 운영 목표의 기록
- `support.webhook.failed`
- 로그 `Support ticket created`, `Support ticket replied`, `Support reply push`, `Support cleanup batch end`

## 설정

```yaml
support:
  attachment-store: rds          # rds | s3 (운영 s3)
  s3: { bucket: ${fill.s3.bucket}, key-prefix: support, presign-minutes: 10 }
  max-attachments: 5
  max-attachment-bytes: 5242880
  public-base-url: https://api.peakpic.app
  ratelimit: { create-daily: 5, message-daily: 30 }
  webhook-url: ${inquiry.webhook-url:}   # 기본은 B2B 문의와 같은 채널
  admin-url: https://peakpic.app/admin/support
cleanup:
  support: { cron: "0 40 5 * * *", retention-days: 365 }
```

운영 전에 확인할 것: 네컷 버킷의 수명 주기 규칙이 `fills/` 접두사에만 걸려 있는지. 버킷 전체면 문의 사진이 7일 뒤 사라지므로 접두사 범위로 바꾸거나 `support.s3.bucket` 을 따로 둔다.
